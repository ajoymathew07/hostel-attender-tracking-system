package in.ac.iitm.hostel.duty.workflow.application;

import in.ac.iitm.hostel.duty.audit.application.AuditDetail;
import in.ac.iitm.hostel.duty.audit.application.AuditEntry;
import in.ac.iitm.hostel.duty.audit.application.AuditTrail;
import in.ac.iitm.hostel.duty.categorization.application.Classification;
import in.ac.iitm.hostel.duty.categorization.application.ComplaintClassifier;
import in.ac.iitm.hostel.duty.dispatch.application.FloorLocator;
import in.ac.iitm.hostel.duty.identity.application.CurrentUser;
import in.ac.iitm.hostel.duty.workflow.domain.ClassificationMethod;
import in.ac.iitm.hostel.duty.workflow.domain.Complaint;
import in.ac.iitm.hostel.duty.workflow.domain.ComplaintDomain;
import in.ac.iitm.hostel.duty.workflow.domain.ComplaintStatus;
import in.ac.iitm.hostel.duty.workflow.domain.Priority;
import in.ac.iitm.hostel.duty.workflow.domain.SafetyKeywords;
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
 * {@link ClassificationMethod#FALLBACK}. This class writes the domain and the floor; where the
 * complaint goes next is {@link AutoDispatchService}'s fork between T2 and T3, and the state move
 * itself is {@link WorkflowService}'s, the only write path to {@code complaint.status} (section
 * 4.1).
 *
 * <p>The floor is derived here because section 5.4 derives it here: the room number is an
 * ingestion field, and what it means as a location is a question for the dispatch module, which
 * {@link FloorLocator} answers. A room that maps to nothing leaves {@code floor_id} null, and the
 * complaint goes to triage rather than being dispatched on a guess.
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

    private final ComplaintRepository complaints;
    private final ComplaintReferenceGenerator references;
    private final AuditTrail audit;
    private final ComplaintClassifier classifier;
    private final SlaClock sla;
    private final FloorLocator floors;
    private final AutoDispatchService dispatch;

    DefaultComplaintIntake(
            ComplaintRepository complaints,
            ComplaintReferenceGenerator references,
            AuditTrail audit,
            ComplaintClassifier classifier,
            SlaClock sla,
            FloorLocator floors,
            AutoDispatchService dispatch) {
        this.complaints = complaints;
        this.references = references;
        this.audit = audit;
        this.classifier = classifier;
        this.sla = sla;
        this.floors = floors;
        this.dispatch = dispatch;
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
        complaint.locateAt(floors.floorIdFor(command.reporterRoom()).orElse(null), null);
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
                                .and("floorId", saved.getFloorId())
                                .and("domain", domain.name())
                                .and("confidence", classification.confidence())
                                .and("priority", saved.getPriority().name())
                                .and("slaDueAt", Objects.toString(saved.getSlaDueAt(), null))
                                .json(),
                        null));

        // T2 or T3 — every complaint leaves intake either assigned or in triage, never at NEW.
        dispatch.dispatch(saved);

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
