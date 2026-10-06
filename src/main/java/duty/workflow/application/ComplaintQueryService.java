package duty.workflow.application;

import duty.workflow.domain.AssignmentStatus;
import duty.workflow.domain.Complaint;
import duty.workflow.domain.ComplaintStatus;
import duty.workflow.domain.TaskAssignment;
import duty.workflow.infrastructure.ComplaintRepository;
import duty.workflow.infrastructure.TaskAssignmentRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The manager's view of every complaint (section 10.3).
 *
 * <p>{@code @PreAuthorize} is on the service, not only on the controller (section 11.3): "view all
 * complaints" is a manager capability, and an attender sees their own assignments alone. A check
 * that lives only in a controller is one new caller away from being bypassed.
 */
@Service
@Transactional(readOnly = true)
@PreAuthorize("hasRole('MANAGER')")
public class ComplaintQueryService {

    /** Newest report first: the manager is looking for what just arrived (section 10.3). */
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "reportedAt", "id");

    /** Somebody is on it — including held work, which is still theirs to come back to. */
    private static final Set<ComplaintStatus> LIVE =
            EnumSet.of(
                    ComplaintStatus.ASSIGNED,
                    ComplaintStatus.INSPECTING,
                    ComplaintStatus.AWAITING_CMS,
                    ComplaintStatus.IN_PROGRESS,
                    ComplaintStatus.ON_HOLD);

    /** The clock runs in these, so only these can be overdue (section 4.5). */
    private static final Set<ComplaintStatus> CLOCK_RUNNING =
            EnumSet.of(
                    ComplaintStatus.TRIAGE,
                    ComplaintStatus.ASSIGNED,
                    ComplaintStatus.INSPECTING,
                    ComplaintStatus.AWAITING_CMS,
                    ComplaintStatus.IN_PROGRESS);

    private final ComplaintRepository complaints;
    private final TaskAssignmentRepository assignments;
    private final ZoneId zone;

    ComplaintQueryService(
            ComplaintRepository complaints,
            TaskAssignmentRepository assignments,
            SlaSettings sla) {
        this.complaints = complaints;
        this.assignments = assignments;
        // "Today" is the manager's day: a complaint resolved at 02:00 IST belongs to that date,
        // not to the UTC one before it.
        this.zone = sla.calendar().zone();
    }

    /** Every complaint, newest report first. */
    public List<ComplaintListItem> listAll() {
        Instant now = Instant.now();
        // One query for every live assignment, not one per row: the manager's list is the screen
        // section 10.6 names when it says "no N+1, fetch joins on queue and dashboard".
        Map<Long, Long> assignee = new HashMap<>();
        for (TaskAssignment assignment : assignments.findAllWithComplaint(AssignmentStatus.ACTIVE)) {
            assignee.put(assignment.getTask().getComplaint().getId(), assignment.getAttenderId());
        }
        return complaints.findAll(NEWEST_FIRST).stream()
                .map(complaint -> toItem(complaint, assignee.get(complaint.getId()), now))
                .toList();
    }

    /**
     * No assignee is looked up. A complaint in {@code TRIAGE} has nobody on it by definition —
     * either it never reached {@code ASSIGNED}, or T8 released whoever held it on the way back.
     */
    public List<ComplaintListItem> listTriage() {
        return complaints.findByStatusOrderByReportedAtAsc(ComplaintStatus.TRIAGE).stream()
                .map(complaint -> toItem(complaint, null, Instant.now()))
                .toList();
    }

    /** How many complaints exist — the dashboard's headline count. */
    public long count() {
        return complaints.count();
    }

    /**
     * Four counts, not a scan of the list.
     *
     * <p>{@link #listAll()} already loads every complaint, so the tiles could be derived from it
     * for free today. They are not, because the list is the thing that will grow a filter and a
     * page, and a tile that silently started counting one page of complaints would be worse than
     * wrong — it would be plausible.
     */
    public DashboardCounters counters() {
        Instant now = Instant.now();
        return new DashboardCounters(
                complaints.countByStatus(ComplaintStatus.TRIAGE),
                complaints.countByStatusIn(LIVE),
                complaints.countByStatusAndResolvedAtAfter(
                        ComplaintStatus.RESOLVED, LocalDate.ofInstant(now, zone).atStartOfDay(zone).toInstant()),
                complaints.countOverdue(CLOCK_RUNNING, now));
    }

    /**
     * {@code slaOverdue} is computed here rather than read from {@code sla_breached}.
     *
     * <p>The flag is written by the aging sweep, which runs on an interval; the manager looking at
     * the list wants to know whether the due date has passed <em>now</em>. Reading the column would
     * show a complaint as on time for up to one tick after it stopped being so.
     */
    private static ComplaintListItem toItem(Complaint complaint, Long assigneeId, Instant now) {
        return new ComplaintListItem(
                complaint.getId(),
                complaint.getPublicRef(),
                complaint.getReportedAt(),
                complaint.getReporterRoom(),
                complaint.getReporterName(),
                complaint.getReporterRollNo(),
                complaint.getReporterPhone(),
                complaint.getRawText(),
                complaint.getDomain(),
                complaint.getClassifiedBy(),
                complaint.getPriority(),
                complaint.getBasePriority(),
                complaint.getSlaDueAt(),
                complaint.getSlaDueAt() != null && now.isAfter(complaint.getSlaDueAt()),
                complaint.getStatus(),
                assigneeId);
    }
}
