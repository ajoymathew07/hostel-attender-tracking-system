package in.ac.iitm.hostel.duty.workflow.application;

import in.ac.iitm.hostel.duty.audit.application.AuditDetail;
import in.ac.iitm.hostel.duty.audit.application.AuditEntry;
import in.ac.iitm.hostel.duty.audit.application.AuditTrail;
import in.ac.iitm.hostel.duty.categorization.application.Classification;
import in.ac.iitm.hostel.duty.categorization.application.ComplaintClassifier;
import in.ac.iitm.hostel.duty.identity.application.CurrentUser;
import in.ac.iitm.hostel.duty.workflow.domain.ClassificationMethod;
import in.ac.iitm.hostel.duty.workflow.domain.Complaint;
import in.ac.iitm.hostel.duty.workflow.domain.ComplaintDomain;
import in.ac.iitm.hostel.duty.workflow.domain.ComplaintStatus;
import in.ac.iitm.hostel.duty.workflow.domain.Priority;
import in.ac.iitm.hostel.duty.workflow.domain.SafetyKeywords;
import in.ac.iitm.hostel.duty.workflow.domain.WorkflowEvent;
import in.ac.iitm.hostel.duty.workflow.infrastructure.ComplaintReferenceGenerator;
import in.ac.iitm.hostel.duty.workflow.infrastructure.ComplaintRepository;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates a complaint from an ingested row — transition T1 (section 4.2), whose side-effect column
 * reads "Create complaint, classify".
 *
 * <p><strong>This is the one place classification happens.</strong> Both sources reconcile through
 * {@code SourceRowIngestor}, which calls this and nothing else of workflow (section 5.8), so the
 * CSV fallback and the Sheets poller are classified by the same call — there is no second path to
 * keep in step.
 *
 * <p>What the lexicon could not place stays {@link ComplaintDomain#UNCLASSIFIED} with
 * {@link ClassificationMethod#FALLBACK} and is moved on to {@code TRIAGE} by transition T3, whose
 * guard is "Domain UNCLASSIFIED <em>or</em> no candidate". The move goes through
 * {@link WorkflowService}, because that is the only write path to {@code complaint.status}
 * (section 4.1) — this class writes the domain, never the state.
 *
 * <p>A <em>classified</em> complaint is left at {@code NEW}. T2 and T3 are the dispatcher's fork
 * and there is no dispatcher yet (a later PR), so the manager assigns it from the complaint list;
 * firing T3 on it would file work the classifier was confident about into the queue of work it was
 * not, which is the opposite of what the triage panel is for.
 *
 * <p>Base priority and the SLA due date are T1's remaining side effects (section 4.5).
 * {@link Priority#P2} is the default, {@link Priority#P1} comes from {@link SafetyKeywords} — an
 * exact match, never a fuzzy one — and P3 only from a manager's downgrade, which is not an intake
 * decision. The due date is measured in working hours from {@code reportedAt}; an
 * {@code UNCLASSIFIED} complaint has no target yet and gets one when a manager sets its domain.
 *
 * <p>{@code @Transactional} with the default propagation, so this <em>joins</em> the caller's
 * per-row transaction: the complaint, its source row and the audit entry commit together or not at
 * all.
 */
@Service
@Transactional
class DefaultComplaintIntake implements ComplaintIntake {

    private static final String ENTITY_TYPE = "complaint";

    /** T3's "record triage reason". It names the classifier, never the resident's words. */
    private static final String TRIAGE_REASON =
            "the classifier could not place this complaint confidently";

    private final ComplaintRepository complaints;
    private final ComplaintReferenceGenerator references;
    private final AuditTrail audit;
    private final ComplaintClassifier classifier;
    private final WorkflowService workflow;
    private final SlaClock sla;

    DefaultComplaintIntake(
            ComplaintRepository complaints,
            ComplaintReferenceGenerator references,
            AuditTrail audit,
            ComplaintClassifier classifier,
            WorkflowService workflow,
            SlaClock sla) {
        this.complaints = complaints;
        this.references = references;
        this.audit = audit;
        this.classifier = classifier;
        this.workflow = workflow;
        this.sla = sla;
    }

    @Override
    public IngestedComplaint record(NewComplaint command) {
        Instant now = Instant.now();
        String publicRef = references.next(command.reportedAt());

        Classification classification = classifier.classify(command.rawText());
        ComplaintDomain domain = domainOf(classification);

        Complaint complaint =
                new Complaint(
                        publicRef,
                        command.rawText(),
                        command.reportedAt(),
                        domain,
                        domain.isDispatchable()
                                ? ClassificationMethod.RULES
                                : ClassificationMethod.FALLBACK,
                        SafetyKeywords.basePriorityFor(command.rawText()),
                        now);
        // Stored whether or not the gate accepted it: section 12.3 measures accuracy over the
        // accepted subset and coverage over the rest, so the rejected items need their number too.
        complaint.recordClassifierConfidence(classification.confidence());
        complaint.recordReporter(
                command.reporterName(),
                command.reporterRollNo(),
                command.reporterPhone(),
                command.reporterContact(),
                command.reporterRoom());
        complaint.linkSourceRow(command.sourceRowId());
        sla.schedule(complaint);

        Complaint saved = complaints.saveAndFlush(complaint);

        // The detail names the reference and the row, never the reporter: audit rows are kept
        // indefinitely, which is precisely what section 11.4's retention limits exist to bound.
        audit.record(
                new AuditEntry(
                        now,
                        CurrentUser.idOrNull(),
                        CurrentUser.roleOrNull(),
                        ENTITY_TYPE,
                        saved.getId(),
                        "COMPLAINT_INGESTED",
                        null,
                        ComplaintStatus.NEW.name(),
                        AuditDetail.of("publicRef", publicRef)
                                .and("sourceRowId", command.sourceRowId())
                                .and("roomKnown", command.reporterRoom() != null)
                                .and("domain", domain.name())
                                .and("confidence", classification.confidence())
                                .and("priority", saved.getPriority().name())
                                .and("slaDueAt", Objects.toString(saved.getSlaDueAt(), null))
                                .json(),
                        null));

        if (!domain.isDispatchable()) {
            workflow.transition(
                    saved.getId(), WorkflowEvent.AUTO_DISPATCH, TransitionPayload.reason(TRIAGE_REASON));
        }

        return new IngestedComplaint(saved.getId(), publicRef);
    }

    /**
     * The lexicon's domain name, as a {@link ComplaintDomain}.
     *
     * <p>{@code category_term.domain} is a manager-editable string with no CHECK constraint behind
     * it, so a typo is a value this enum has never heard of. An unknown name routes to triage,
     * which is where an uncertain complaint belongs anyway — it does not stop the ingestion run.
     */
    private static ComplaintDomain domainOf(Classification classification) {
        return Arrays.stream(ComplaintDomain.values())
                .filter(candidate -> candidate.name().equals(classification.domain()))
                .findFirst()
                .orElse(ComplaintDomain.UNCLASSIFIED);
    }
}
