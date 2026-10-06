package duty.workflow.application;

import duty.audit.application.AuditDetail;
import duty.audit.application.AuditEntry;
import duty.audit.application.AuditTrail;
import duty.dispatch.application.FloorLocator;
import duty.identity.application.CurrentUser;
import duty.workflow.domain.ClassificationMethod;
import duty.workflow.domain.Complaint;
import duty.workflow.domain.ComplaintDomain;
import duty.workflow.domain.ComplaintStatus;
import duty.workflow.domain.Priority;
import duty.workflow.domain.SafetyKeywords;
import duty.workflow.infrastructure.ComplaintReferenceGenerator;
import duty.workflow.infrastructure.ComplaintRepository;
import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates a complaint from an ingested row — transition T1 (section 4.2).
 *
 * <p><strong>The resident says which domain it is.</strong> The Form asks — Civil, Electrical,
 * LAN, RO or Others — and {@link ComplaintDomain#reported} turns that answer into the enum. There
 * is no classifier: a resident choosing from five options is better evidence than a term list
 * inferring from their prose, and the inference was never free — it cost a lexicon table, a
 * fuzzy matcher, two thresholds and a gold set to measure whether any of it worked.
 *
 * <p>What that machinery bought is still here, unchanged, because it was never really about the
 * lexicon: a complaint whose domain nobody has settled goes to {@code TRIAGE} with a reason and a
 * manager places it in one click. "Others" is a resident saying they do not know, and an
 * unanswered question is the same thing more quietly — both land in the same queue.
 *
 * <p><strong>This is the one place a domain is read from a form.</strong> Both sources reconcile
 * through {@code SourceRowIngestor}, which calls this and nothing else of workflow (section 5.8),
 * so the CSV fallback and the Sheets poller behave identically — there is no second path to keep
 * in step.
 *
 * <p>The floor is derived here too, because section 5.4 derives it here: the room number is an
 * ingestion field, and what it means as a location is a question for the dispatch module, which
 * {@link FloorLocator} answers. A room that maps to nothing leaves {@code floor_id} null and the
 * complaint goes to triage rather than being dispatched on a guess.
 *
 * <p>Base priority and the SLA due date are T1's remaining side effects (section 4.5).
 * {@link Priority#P2} is the default, {@link Priority#P1} comes from {@link SafetyKeywords} — an
 * exact match, never a fuzzy one — and P3 only from a manager's downgrade, which is not an intake
 * decision. The due date is measured in working hours from {@code reportedAt}; an
 * {@code UNCLASSIFIED} complaint has no target yet and gets one when a manager sets its domain.
 *
 * <p>{@code @Transactional} with the default propagation, so this <em>joins</em> the caller's
 * per-row transaction: the complaint, its source row, the dispatch decision and the audit entry
 * commit together or not at all.
 */
@Service
@Transactional
class DefaultComplaintIntake implements ComplaintIntake {

    private static final String ENTITY_TYPE = "complaint";

    private final ComplaintRepository complaints;
    private final ComplaintReferenceGenerator references;
    private final AuditTrail audit;
    private final SlaClock sla;
    private final FloorLocator floors;
    private final AutoDispatchService dispatch;

    DefaultComplaintIntake(
            ComplaintRepository complaints,
            ComplaintReferenceGenerator references,
            AuditTrail audit,
            SlaClock sla,
            FloorLocator floors,
            AutoDispatchService dispatch) {
        this.complaints = complaints;
        this.references = references;
        this.audit = audit;
        this.sla = sla;
        this.floors = floors;
        this.dispatch = dispatch;
    }

    @Override
    public IngestedComplaint record(NewComplaint command) {
        Instant now = Instant.now();
        String publicRef = references.next(command.reportedAt());

        ComplaintDomain domain = ComplaintDomain.reported(command.reportedDomain());

        Complaint complaint =
                new Complaint(
                        publicRef,
                        command.rawText(),
                        command.reportedAt(),
                        domain,
                        domain.isDispatchable()
                                ? ClassificationMethod.REPORTED
                                : ClassificationMethod.FALLBACK,
                        SafetyKeywords.basePriorityFor(command.rawText()),
                        now);
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
        // The resident's own answer is recorded verbatim beside what it was read as, so a Form
        // option nobody mapped shows up as a run of triaged complaints with the answer on them.
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
                                .and("reportedDomain", command.reportedDomain())
                                .and("domain", domain.name())
                                .and("priority", saved.getPriority().name())
                                .and("slaDueAt", Objects.toString(saved.getSlaDueAt(), null))
                                .json(),
                        null));

        // T2 or T3 — every complaint leaves intake either assigned or in triage, never at NEW.
        dispatch.dispatch(saved);

        return new IngestedComplaint(saved.getId(), publicRef);
    }
}
