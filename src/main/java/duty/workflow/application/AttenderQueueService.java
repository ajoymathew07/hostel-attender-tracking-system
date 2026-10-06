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
 * The attender's own queue (sections 10.2, 11.3).
 *
 * <p>There is no {@code hasRole('ATTENDER')} on this class and that is deliberate. The scope is
 * not "attenders may see assignments", it is "you may see <em>yours</em>" — the query is keyed on
 * the signed-in user, so a role check would add nothing a caller could get round and would refuse
 * the manager's {@code act-as} path (section 9.4) when it lands. The detail view carries the
 * ownership check instead, which is the one section 11.3 actually names.
 */
@Service
@Transactional(readOnly = true)
public class AttenderQueueService {

    private final TaskAssignmentRepository assignments;

    AttenderQueueService(TaskAssignmentRepository assignments) {
        this.assignments = assignments;
    }

    /**
     * The signed-in attender's live work, highest priority first.
     *
     * <p>Scoped to the caller rather than taking an id: an attender id in the signature is an
     * attender id someone can change in a form post (E20).
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
     * One task of the caller's, for {@code /m/tasks/{id}} (section 10.4).
     *
     * <p>{@code @access.ownsTask} is the ownership half of section 11.3, and it is on the service
     * rather than the controller so that a deep link from a push notification, a future API or a
     * second screen all meet the same check.
     */
    @PreAuthorize("@access.ownsTask(#taskId)")
    public Optional<QueuedTask> myTask(long taskId) {
        Instant now = Instant.now();
        return CurrentUser.get()
                .flatMap(user -> assignments.findByTaskIdAndAttenderId(taskId, user.getUserId()))
                .map(assignment -> toCard(assignment, now));
    }

    /**
     * Builds the card, applying the 7-day window of section 11.4.
     *
     * <p>The three student fields are dropped here rather than in the template, so an expired
     * window means the values never enter the model at all.
     *
     * <p>{@code slaOverdue} is answered against {@code now} rather than read from
     * {@code sla_breached}: the flag is written by the aging sweep on an interval, and the attender
     * holding the phone wants to know whether the due date has passed this second.
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
