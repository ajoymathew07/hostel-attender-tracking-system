package in.ac.iitm.hostel.duty.workflow.application;

import in.ac.iitm.hostel.duty.audit.application.AuditDetail;
import in.ac.iitm.hostel.duty.audit.application.AuditEntry;
import in.ac.iitm.hostel.duty.audit.application.AuditTrail;
import in.ac.iitm.hostel.duty.identity.application.CurrentUser;
import in.ac.iitm.hostel.duty.workflow.domain.ClassificationMethod;
import in.ac.iitm.hostel.duty.workflow.domain.Complaint;
import in.ac.iitm.hostel.duty.workflow.domain.ComplaintDomain;
import in.ac.iitm.hostel.duty.workflow.domain.ComplaintStatus;
import in.ac.iitm.hostel.duty.workflow.domain.Priority;
import in.ac.iitm.hostel.duty.workflow.infrastructure.ComplaintReferenceGenerator;
import in.ac.iitm.hostel.duty.workflow.infrastructure.ComplaintRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates a complaint from an ingested row — transition T1 (section 4.2).
 *
 * <p>Two defaults are deliberate and temporary:
 *
 * <ul>
 *   <li>{@link ComplaintDomain#UNCLASSIFIED} with {@link ClassificationMethod#FALLBACK}. The
 *       weighted lexicon arrives in PR 10; until then nothing has cleared a confidence gate,
 *       and section 5.5 is explicit that an unclassified complaint is a real outcome routed to the
 *       manager's triage queue, never a guess at a domain. Recording {@code FALLBACK} rather than
 *       {@code RULES} keeps that honest in the data.
 *   <li>{@link Priority#P2}, which section 4.5 gives as the default. P1 comes from the safety
 *       keyword set and P3 from a manager downgrade, and neither exists yet (PR 12).
 * </ul>
 *
 * <p>The complaint is left in {@code NEW}. Moving it on to {@code TRIAGE} is transition T3, and
 * T3 belongs to the state machine that PR 11 builds; inventing half of it here would mean PR 11
 * had to unpick it.
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

    DefaultComplaintIntake(
            ComplaintRepository complaints,
            ComplaintReferenceGenerator references,
            AuditTrail audit) {
        this.complaints = complaints;
        this.references = references;
        this.audit = audit;
    }

    @Override
    public IngestedComplaint record(NewComplaint command) {
        Instant now = Instant.now();
        String publicRef = references.next(command.reportedAt());

        Complaint complaint =
                new Complaint(
                        publicRef,
                        command.rawText(),
                        command.reportedAt(),
                        ComplaintDomain.UNCLASSIFIED,
                        ClassificationMethod.FALLBACK,
                        Priority.P2,
                        now);
        complaint.recordReporter(
                command.reporterName(),
                command.reporterRollNo(),
                command.reporterPhone(),
                command.reporterContact(),
                command.reporterRoom());
        complaint.linkSourceRow(command.sourceRowId());

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
                                .json(),
                        null));

        return new IngestedComplaint(saved.getId(), publicRef);
    }
}
