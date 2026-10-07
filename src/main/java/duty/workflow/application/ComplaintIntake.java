package duty.workflow.application;

import duty.audit.application.AuditTrail.AuditDetail;
import duty.audit.application.AuditTrail.AuditEntry;
import duty.audit.application.AuditTrail;
import duty.dispatch.application.AttenderDispatcher.FloorLocator;
import duty.identity.application.CurrentUser;
import duty.workflow.application.SlaSettings.SlaClock;
import duty.workflow.domain.Complaint.ClassificationMethod;
import duty.workflow.domain.Complaint.ComplaintDomain;
import duty.workflow.domain.Complaint.ComplaintStatus;
import duty.workflow.domain.Complaint.Priority;
import duty.workflow.domain.Complaint.SafetyKeywords;
import duty.workflow.domain.Complaint;
import duty.workflow.infrastructure.ComplaintReferenceGenerator;
import duty.workflow.infrastructure.ComplaintRepository;
import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates a new complaint from an ingested row (T1). The only entry point ingestion uses, for both
 * CSV and Sheets.
 *
 * <p>On creation it:
 * <ul>
 *   <li>maps the category the resident chose to a domain ("Others" or blank means triage);
 *   <li>finds the floor from the room number;
 *   <li>sets the base priority (P1 if a safety keyword appears, otherwise P2) and the SLA due date;
 *   <li>runs auto-dispatch.
 * </ul>
 *
 * <p>No {@code @PreAuthorize}: the Sheets poller runs with nobody signed in. The CSV upload checks the
 * manager role itself. Joins the caller's per-row transaction so everything commits together.
 */
@Service
@Transactional
public class ComplaintIntake {

    private static final String ENTITY_TYPE = "complaint";

    private final ComplaintRepository complaints;
    private final ComplaintReferenceGenerator references;
    private final AuditTrail audit;
    private final SlaClock sla;
    private final FloorLocator floors;
    private final AutoDispatchService dispatch;

    ComplaintIntake(
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

    /** Creates a complaint from an ingested row, inside the caller's transaction. */
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

        // No reporter details in the audit row (kept forever). The resident's category answer is
        // recorded beside the domain it mapped to, so an unmapped Form option is easy to spot.
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

    /**
     * The data ingestion passes in to create a complaint. Plain values only, so ingestion never touches the
     * {@code Complaint} entity.
     *
     * @param rawText the resident's wording, exactly as typed
     * @param reportedAt the form timestamp, in UTC
     * @param reporterContact the resident's email; purged after 90 days
     * @param reporterRoom the room number, or {@code null} (goes to triage)
     * @param reportedDomain the category chosen on the form; anything unrecognised goes to triage
     * @param sourceRowId the source row this complaint came from
     */
    public record NewComplaint(
            String rawText,
            Instant reportedAt,
            String reporterName,
            String reporterRollNo,
            String reporterPhone,
            String reporterContact,
            String reporterRoom,
            String reportedDomain,
            Long sourceRowId) {

        public NewComplaint {
            Objects.requireNonNull(rawText, "rawText");
            Objects.requireNonNull(reportedAt, "reportedAt");
        }

        /** Leaves out the student's details and the complaint text, so they cannot reach a log. */
        @Override
        public String toString() {
            return "NewComplaint[reportedAt=" + reportedAt + ", sourceRowId=" + sourceRowId + "]";
        }
    }

    /**
     * Returned after a complaint is created.
     *
     * @param publicRef the human-facing reference, e.g. {@code CAU-2026-0417}
     */
    public record IngestedComplaint(long id, String publicRef) {}
}
