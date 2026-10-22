package duty.workflow.application;

import duty.identity.application.AccessGuard.TaskAssignmentLookup;
import duty.identity.application.CurrentUser;
import duty.workflow.domain.Complaint.ComplaintDomain;
import duty.workflow.domain.Complaint.ComplaintStatus;
import duty.workflow.domain.Complaint.Priority;
import duty.workflow.domain.Complaint;
import duty.workflow.domain.TaskAssignment.AssignmentStatus;
import duty.workflow.domain.TaskAssignment;
import duty.workflow.infrastructure.TaskAssignmentRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The signed-in attender's own queue.
 *
 * <p>No role check: the query only ever returns the caller's own work. Opening a single task is
 * guarded by ownership.
 */
@Service
@Transactional(readOnly = true)
public class AttenderQueueService {

    private final TaskAssignmentRepository assignments;

    AttenderQueueService(TaskAssignmentRepository assignments) {
        this.assignments = assignments;
    }

    /**
     * The caller's live work, highest priority first. Takes no attender id, so nobody can ask for someone
     * else's.
     */
    public List<QueuedTask> myQueue() {
        Instant now = Instant.now();
        return CurrentUser.get()
                .map(
                        user ->
                                assignments
                                        .findQueue(user.getUserId(), AssignmentStatus.ACTIVE)
                                        .stream()
                                        .map(assignment -> toCard(assignment, now))
                                        .toList())
                .orElseGet(List::of);
    }

    /**
     * One of the caller's tasks, for {@code /m/tasks/{id}}. The ownership check is on the service so every
     * route to it is covered.
     */
    @PreAuthorize("@access.ownsTask(#taskId)")
    public Optional<QueuedTask> myTask(long taskId) {
        Instant now = Instant.now();
        return CurrentUser.get()
                .flatMap(user -> assignments.findByTaskIdAndAttenderId(taskId, user.getUserId()))
                // A special duty has no complaint to show; it lives on /m/duties.
                .filter(assignment -> assignment.getTask().getComplaint() != null)
                .map(assignment -> toCard(assignment, now));
    }

    /**
     * Builds the card. Student details are left out once the 7-day window after resolution has passed, so
     * they never reach the page. Overdue is computed against now, not read from the periodically updated
     * flag.
     */
    private static QueuedTask toCard(TaskAssignment assignment, Instant now) {
        Complaint complaint = assignment.getTask().getComplaint();
        boolean showStudent = complaint.reporterVisibleToAttender(now);
        return new QueuedTask(
                assignment.getTask().getId(),
                complaint.getPublicRef(),
                complaint.getRawText(),
                complaint.getDomain(),
                complaint.getPriority(),
                complaint.getBasePriority(),
                complaint.getSlaDueAt() != null && now.isAfter(complaint.getSlaDueAt()),
                complaint.getStatus(),
                complaint.getReporterRoom(),
                complaint.getDocketNo(),
                complaint.getSlaDueAt(),
                assignment.getAssignedAt(),
                showStudent ? complaint.getReporterName() : null,
                showStudent ? complaint.getReporterRollNo() : null,
                showStudent ? complaint.getReporterPhone() : null);
    }

    /**
     * One card on the attender's queue.
     *
     * <p>Student details are {@code null} once the 7-day window after resolution has passed; they are
     * removed here so they never reach the page. The {@code can...} methods tell the template which single
     * action to show; the state machine still enforces it.
     */
    public record QueuedTask(
            long taskId,
            String publicRef,
            String rawText,
            ComplaintDomain domain,
            Priority priority,
            Priority basePriority,
            boolean slaOverdue,
            ComplaintStatus status,
            String room,
            String docketNo,
            Instant slaDueAt,
            Instant assignedAt,
            String studentName,
            String studentRollNo,
            String studentPhone) {

        /** Whether ageing raised this priority. */
        public boolean aged() {
            return priority != basePriority;
        }

        /** Can start work (T7). */
        public boolean canStart() {
            return status == ComplaintStatus.ASSIGNED;
        }

        /** Can report what was found on site: a defect or nothing wrong (T9, T10). */
        public boolean canReportInspection() {
            return status == ComplaintStatus.INSPECTING;
        }

        /** Can enter or correct the docket number (T11). */
        public boolean canFileDocket() {
            return status == ComplaintStatus.AWAITING_CMS || status == ComplaintStatus.IN_PROGRESS;
        }

        /** Can resolve: only once a docket is filed (T14). */
        public boolean canResolve() {
            return docketNo != null && !docketNo.isBlank();
        }

        /** Whether the student's contact details may still be shown. */
        public boolean hasStudentContact() {
            return studentName != null || studentRollNo != null || studentPhone != null;
        }

        /** The reference only, so student details cannot reach a log. */
        @Override
        public String toString() {
            return "QueuedTask[" + taskId + ", " + publicRef + ", " + status + ", " + priority + "]";
        }
    }

    /**
     * Implements {@code @access.ownsTask}: does this user hold an active assignment for the task? It lives
     * here because assignments are this module's table.
     */
    @Component
    static class ActiveTaskAssignments implements TaskAssignmentLookup {

        private final TaskAssignmentRepository assignments;

        ActiveTaskAssignments(TaskAssignmentRepository assignments) {
            this.assignments = assignments;
        }

        /**
         * Only {@code ACTIVE} counts. A {@code RELEASED} row means the attender used to hold the task and may
         * no longer act on it.
         */
        @Override
        public boolean holdsActiveAssignment(long userId, long taskId) {
            return assignments
                    .findByTaskIdAndAttenderId(taskId, userId)
                    .filter(TaskAssignment::isActive)
                    .isPresent();
        }
    }
}
