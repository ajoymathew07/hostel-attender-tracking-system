package duty.workflow.application;

import duty.audit.application.AuditDetail;
import duty.audit.application.AuditEntry;
import duty.audit.application.AuditTrail;
import duty.identity.application.CurrentUser;
import duty.workflow.domain.Complaint;
import duty.workflow.domain.ComplaintStatus;
import duty.workflow.domain.Priority;
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
 * What moves a complaint's priority after intake: a manager's downgrade, and the aging sweep
 * (section 4.5).
 *
 * <p>Both live here because they write the same two columns under the same rule — {@code priority}
 * moves, {@code base_priority} records what it started as — and because their interaction is the
 * part that has to be right: an aged P3 shows as "P1 (aged from P3)" only if the downgrade wrote
 * the base and the sweep did not.
 *
 * <p>Priority is not status, so this does not go through {@link WorkflowService}. Section 4.1 makes
 * that service the single write path to {@code complaint.status} specifically, and neither of these
 * changes a complaint's state.
 */
@Service
@Transactional
public class PriorityService {

    private static final String ENTITY_TYPE = "complaint";

    /**
     * The states in which the SLA clock is running.
     *
     * <p>{@code RESOLVED} is excluded because section 4.5 escalates only work that has not reached
     * it; {@code CLOSED} and {@code REJECTED} are finished; and {@code ON_HOLD} is excluded because
     * hold time does not count toward aging (T12) — the complaint is waiting on the Engineering
     * Unit, not on an attender.
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

    PriorityService(ComplaintRepository complaints, SlaClock sla, AuditTrail audit) {
        this.complaints = complaints;
        this.sla = sla;
        this.audit = audit;
    }

    /**
     * Section 4.5's third row: the manager judges this less urgent than it arrived.
     *
     * <p>Manager-only, and on the service rather than only the controller (section 11.3): a check
     * that lives in a controller is one new caller away from being bypassed. The due date is
     * recomputed, because a P3 window is a different length from the one the complaint arrived with
     * — leaving the old date would have the list call a downgraded complaint late on the strength of
     * a target nobody holds it to any more.
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
     * The aging sweep of section 4.5: one escalation step per overdue complaint, and the breach flag.
     *
     * <p>The query is coarse on purpose — every complaint whose due date has passed, whatever its
     * priority — and the 1.5× arithmetic is applied in memory. The exact threshold depends on the
     * complaint's own window, so it is not a column SQL could compare against, and at ~20 complaints
     * a day (section 8.3) the overdue set is small. A generated column or a second stored date would
     * be a cache of something the clock can answer.
     *
     * <p>One step per run, capped at {@code P1}, so a complaint that is already {@code P1} is left
     * alone and section 4.5's {@code P3 → P2 → P1} runs in order.
     *
     * @return how many complaints were escalated, for the log
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

    /** The escalation, and the audit row that lets the dashboard explain it. */
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
    }
}
