package duty.workflow.application;

import duty.audit.application.AuditTrail.AuditDetail;
import duty.audit.application.AuditTrail.AuditEntry;
import duty.audit.application.AuditTrail;
import duty.identity.application.CurrentUser;
import duty.workflow.application.SlaSettings.SlaClock;
import duty.workflow.domain.Complaint.ComplaintStatus;
import duty.workflow.domain.Complaint.Priority;
import duty.workflow.domain.Complaint;
import duty.workflow.infrastructure.ComplaintRepository;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Changes a complaint's priority after intake: the manager's downgrade, and the ageing job.
 *
 * <p>Both write {@code priority} and {@code base_priority} under one rule, so they live together.
 * Priority is not status, so this does not go through {@link WorkflowService}.
 */
@Service
@Transactional
public class PriorityService {

    private static final String ENTITY_TYPE = "complaint";

    /**
     * States in which the SLA clock runs. {@code ON_HOLD} is excluded because hold time does not count
     * toward ageing.
     */
    private static final Set<ComplaintStatus> CLOCK_RUNNING =
            EnumSet.of(
                    ComplaintStatus.NEW,
                    ComplaintStatus.TRIAGE,
                    ComplaintStatus.ASSIGNED,
                    ComplaintStatus.INSPECTING,
                    ComplaintStatus.AWAITING_CMS,
                    ComplaintStatus.IN_PROGRESS);

    private final ComplaintRepository complaints;
    private final SlaClock sla;
    private final AuditTrail audit;
    private final AttenderAlerts alerts;

    PriorityService(ComplaintRepository complaints, SlaClock sla, AuditTrail audit, AttenderAlerts alerts) {
        this.complaints = complaints;
        this.sla = sla;
        this.audit = audit;
        this.alerts = alerts;
    }

    /**
     * Downgrades a complaint to P3. Manager-only. The due date is recalculated for the new window.
     */
    @PreAuthorize("hasRole('MANAGER')")
    public void downgrade(long complaintId) {
        Complaint complaint = complaints.findById(complaintId).orElseThrow();
        Priority previous = complaint.getPriority();
        Instant now = Instant.now();

        complaint.downgrade(now);
        sla.schedule(complaint);
        complaints.saveAndFlush(complaint);

        audit.record(
                new AuditEntry(
                        now,
                        CurrentUser.idOrNull(),
                        CurrentUser.roleOrNull(),
                        ENTITY_TYPE,
                        complaint.getId(),
                        "PRIORITY_DOWNGRADED",
                        previous.name(),
                        Priority.P3.name(),
                        AuditDetail.of("slaDueAt", Objects.toString(complaint.getSlaDueAt(), null)).json(),
                        null));
    }

    /**
     * The ageing job: raises each complaint open past 1.5× its SLA window by one step (P3 → P2 → P1), and
     * sets the breach flag.
     *
     * <p>The query fetches all overdue complaints and the 1.5× check is done in Java, since the threshold
     * depends on each complaint's own window.
     *
     * @return how many complaints were escalated
     */
    public int ageOpenComplaints() {
        Instant now = Instant.now();
        List<Complaint> overdue = complaints.findByStatusInAndSlaDueAtBefore(CLOCK_RUNNING, now);
        int escalated = 0;
        for (Complaint complaint : overdue) {
            if (!complaint.isSlaBreached()) {
                complaint.markSlaBreached();
            }
            if (dueAnEscalation(complaint, now)) {
                escalate(complaint, now);
                escalated++;
            }
        }
        // Managed entities inside this transaction, so the flush is the commit's; saveAndFlush per
        // row would only add round trips.
        return escalated;
    }

    private boolean dueAnEscalation(Complaint complaint, Instant now) {
        if (complaint.getPriority() == Priority.P1) {
            return false;
        }
        Optional<Instant> ageingDueAt = sla.ageingDueAt(complaint);
        return ageingDueAt.isPresent() && !now.isBefore(ageingDueAt.get());
    }

    /** Escalates one complaint and audits it. */
    private void escalate(Complaint complaint, Instant now) {
        Priority previous = complaint.getPriority();
        complaint.escalatePriority(now);
        audit.record(
                new AuditEntry(
                        now,
                        null,
                        null,
                        ENTITY_TYPE,
                        complaint.getId(),
                        "PRIORITY_AGED",
                        previous.name(),
                        complaint.getPriority().name(),
                        AuditDetail.of("basePriority", complaint.getBasePriority().name())
                                .and("slaDueAt", Objects.toString(complaint.getSlaDueAt(), null))
                                .json(),
                        null));
        alerts.escalated(complaint);
    }
}
