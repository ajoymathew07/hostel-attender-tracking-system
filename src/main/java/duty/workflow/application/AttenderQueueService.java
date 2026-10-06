package duty.workflow.application;

import duty.identity.application.CurrentUser;
import duty.workflow.domain.AssignmentStatus;
import duty.workflow.domain.Complaint;
import duty.workflow.domain.TaskAssignment;
import duty.workflow.infrastructure.TaskAssignmentRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.security.access.prepost.PreAuthorize;
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
}
