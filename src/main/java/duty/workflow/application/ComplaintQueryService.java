package duty.workflow.application;

import duty.audit.application.AuditTrail.AuditRecord;
import duty.audit.application.AuditTrail;
import duty.ledger.application.PointsLedger.LedgerLine;
import duty.ledger.application.PointsLedger;
import duty.workflow.domain.Complaint.ClassificationMethod;
import duty.workflow.domain.Complaint.ComplaintDomain;
import duty.workflow.domain.Complaint.ComplaintStatus;
import duty.workflow.domain.Complaint.Priority;
import duty.workflow.domain.Complaint;
import duty.workflow.domain.Task;
import duty.workflow.domain.TaskAssignment.AssignmentStatus;
import duty.workflow.domain.TaskAssignment;
import duty.workflow.infrastructure.ComplaintRepository;
import duty.workflow.infrastructure.TaskAssignmentRepository;
import duty.workflow.infrastructure.TaskRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private final TaskRepository tasks;
    private final TaskAssignmentRepository assignments;
    private final AuditTrail audit;
    private final PointsLedger ledger;
    private final ZoneId zone;

    ComplaintQueryService(
            ComplaintRepository complaints,
            TaskRepository tasks,
            TaskAssignmentRepository assignments,
            AuditTrail audit,
            PointsLedger ledger,
            SlaSettings sla) {
        this.complaints = complaints;
        this.tasks = tasks;
        this.assignments = assignments;
        this.audit = audit;
        this.ledger = ledger;
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
    /**
     * One complaint with everything that happened to it: its transitions, its assignments and the points
     * credited or reversed for it, oldest first.
     */
    public Optional<ComplaintHistory> history(long complaintId) {
        return complaints
                .findById(complaintId)
                .map(
                        complaint -> {
                            List<AuditRecord> events = new ArrayList<>(audit.historyOf("complaint", complaintId));
                            Long holder = null;
                            List<LedgerLine> points = List.of();
                            Optional<Task> task = tasks.findByComplaintId(complaintId);
                            if (task.isPresent()) {
                                long taskId = task.get().getId();
                                events.addAll(audit.historyOf("task", taskId));
                                events.sort(Comparator.comparing(AuditRecord::occurredAt));
                                holder =
                                        assignments.findByTaskIdAndStatus(taskId, AssignmentStatus.ACTIVE).stream()
                                                .map(TaskAssignment::getAttenderId)
                                                .findFirst()
                                                .orElse(null);
                                points = ledger.linesForTask(taskId);
                            }
                            return new ComplaintHistory(
                                    toItem(complaint, holder, Instant.now()),
                                    complaint.getDocketNo(),
                                    events,
                                    points);
                        });
    }

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

    /**
     * One row of the manager's complaint list. Includes the student's details, which the manager may always
     * see.
     */
    public record ComplaintListItem(
            long id,
            String publicRef,
            Instant reportedAt,
            String reporterRoom,
            String reporterName,
            String reporterRollNo,
            String reporterPhone,
            String rawText,
            ComplaintDomain domain,
            ClassificationMethod classifiedBy,
            Priority priority,
            Priority basePriority,
            Instant slaDueAt,
            boolean slaOverdue,
            ComplaintStatus status,
            Long assigneeId) {

        /**
         * Whether the priority was raised by ageing rather than set at intake, so the list can show "P1 (aged
         * from P3)".
         */
        public boolean aged() {
            return priority != basePriority;
        }

        /** Leaves out the student's name, roll number and phone. */
        @Override
        public String toString() {
            return "ComplaintListItem[" + publicRef + ", " + status + ", " + priority + "]";
        }
    }

    /** A complaint's history page: the complaint, its docket, every recorded change and its points. */
    public record ComplaintHistory(
            ComplaintListItem complaint, String docketNo, List<AuditRecord> events, List<LedgerLine> points) {}

    /**
     * The counts across the top of the manager's dashboard.
     *
     * <p>No "new" count: auto-dispatch runs during intake, so a complaint is already assigned or in triage
     * when the manager first sees it.
     *
     * @param triage waiting for the manager
     * @param active someone is on it, including work on hold
     * @param resolvedToday resolved today, in local time
     * @param overdue open complaints past their due date
     */
    public record DashboardCounters(long triage, long active, long resolvedToday, long overdue) {}
}
