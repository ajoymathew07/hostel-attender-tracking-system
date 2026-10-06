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

/** The manager's view of every complaint. Manager-only, checked on the service. */
@Service
@Transactional(readOnly = true)
@PreAuthorize("hasRole('MANAGER')")
public class ComplaintQueryService {

    /** Newest first: the manager is looking for what just arrived. */
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "reportedAt", "id");

    /** Someone is on it, including work on hold. */
    private static final Set<ComplaintStatus> LIVE =
            EnumSet.of(
                    ComplaintStatus.ASSIGNED,
                    ComplaintStatus.INSPECTING,
                    ComplaintStatus.AWAITING_CMS,
                    ComplaintStatus.IN_PROGRESS,
                    ComplaintStatus.ON_HOLD);

    /** States in which the SLA clock runs, so only these can be overdue. */
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
     * The triage queue, oldest first: complaints the resident did not place in a domain, or that could
     * not be dispatched.
     */
    public List<ComplaintListItem> listTriage() {
        return complaints.findByStatusOrderByReportedAtAsc(ComplaintStatus.TRIAGE).stream()
                .map(complaint -> toItem(complaint, null, Instant.now()))
                .toList();
    }

    /** Total number of complaints. */
    public long count() {
        return complaints.count();
    }

    /**
     * The dashboard tiles, counted by queries rather than from the list, so they stay right if the list
     * is ever paged.
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
     * Builds a list row. Overdue is computed against now rather than read from the periodically updated
     * flag.
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
