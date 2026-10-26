package duty.workflow.application;

import duty.identity.application.CurrentUser.SignedInUser;
import duty.identity.application.CurrentUser;
import duty.ledger.application.PointsLedger;
import duty.workflow.application.WorkflowService.DocketRequiredException;
import duty.workflow.application.WorkflowService.GuardFailedException;
import duty.workflow.application.WorkflowService.TransitionPayload;
import duty.workflow.domain.Complaint;
import duty.workflow.domain.ComplaintTransitions.WorkflowEvent;
import duty.workflow.domain.Task;
import duty.workflow.domain.TaskAssignment.AssignmentStatus;
import duty.workflow.domain.TaskAssignment;
import duty.workflow.infrastructure.TaskAssignmentRepository;
import duty.workflow.infrastructure.TaskRepository;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The attender's steps on their phone: start (T7), defect or no defect (T9, T10), docket (T11),
 * resolve (T14).
 *
 * <p>Status changes go through {@link WorkflowService}. Every action checks that the caller holds the
 * task, not just that they are an attender, so nobody can resolve someone else's work for the points.
 * {@link #resolve} loads the assignment itself because it must treat a repeated tap as success.
 */
@Service
@Transactional
public class TaskResolutionService {

    /** The steps a manager may take for an attender during roll-call (act-as, section 2.1). */
    public static final Set<WorkflowEvent> ATTENDER_STEPS =
            EnumSet.of(
                    WorkflowEvent.ATTENDER_START,
                    WorkflowEvent.DEFECT_CONFIRMED,
                    WorkflowEvent.NO_DEFECT_FOUND,
                    WorkflowEvent.DOCKET_RECORDED,
                    WorkflowEvent.BLOCK,
                    WorkflowEvent.UNBLOCK,
                    WorkflowEvent.MARK_RESOLVED);

    private final TaskRepository tasks;
    private final TaskAssignmentRepository assignments;
    private final PointsLedger ledger;
    private final WorkflowService workflow;

    TaskResolutionService(
            TaskRepository tasks,
            TaskAssignmentRepository assignments,
            PointsLedger ledger,
            WorkflowService workflow) {
        this.tasks = tasks;
        this.assignments = assignments;
        this.ledger = ledger;
        this.workflow = workflow;
    }

    /** Start work (T7). */
    @PreAuthorize("@access.ownsTask(#taskId)")
    public void startInspection(long taskId) {
        workflow.transition(
                complaintIdOf(taskId), WorkflowEvent.ATTENDER_START, TransitionPayload.none());
    }

    /** A defect was found (T9); a docket is now needed. */
    @PreAuthorize("@access.ownsTask(#taskId)")
    public void confirmDefect(long taskId) {
        workflow.transition(
                complaintIdOf(taskId), WorkflowEvent.DEFECT_CONFIRMED, TransitionPayload.none());
    }

    /**
     * Nothing wrong on site (T10): the complaint closes with no points. A note is required to say why.
     */
    @PreAuthorize("@access.ownsTask(#taskId)")
    public void reportNoDefect(long taskId, String note) {
        workflow.transition(
                complaintIdOf(taskId), WorkflowEvent.NO_DEFECT_FOUND, TransitionPayload.reason(note));
    }

    /**
     * Records the Engineering Unit docket number (T11). A correction overwrites it; the audit log keeps
     * the old value.
     */
    @PreAuthorize("@access.ownsTask(#taskId)")
    public void recordDocket(long taskId, String docketNo) {
        workflow.transition(
                complaintIdOf(taskId), WorkflowEvent.DOCKET_RECORDED, TransitionPayload.docket(docketNo));
    }

    /**
     * Act-as: the manager takes an attender's step for the attender who holds the work, when that person
     * cannot use their phone. The same state machine and guards run; the audit row names the manager and
     * the attender, and any points go to the attender.
     *
     * @param value the note for no-defect or block, or the docket number; ignored otherwise
     */
    @PreAuthorize("hasRole('MANAGER')")
    public void actAs(long complaintId, WorkflowEvent step, String value) {
        if (!ATTENDER_STEPS.contains(step)) {
            throw new GuardFailedException("only an attender's step can be taken for them");
        }
        long holder =
                tasks.findByComplaintId(complaintId)
                        .flatMap(
                                task ->
                                        assignments
                                                .findByTaskIdAndStatus(task.getId(), AssignmentStatus.ACTIVE)
                                                .stream()
                                                .findFirst())
                        .map(TaskAssignment::getAttenderId)
                        .orElseThrow(() -> new GuardFailedException("nobody holds this complaint"));
        TransitionPayload payload =
                switch (step) {
                    case NO_DEFECT_FOUND, BLOCK -> TransitionPayload.reason(value);
                    case DOCKET_RECORDED -> TransitionPayload.docket(value);
                    default -> TransitionPayload.none();
                };
        workflow.transition(complaintId, step, payload.actingFor(holder));
    }

    /** The complaint id for a task. */
    private long complaintIdOf(long taskId) {
        return tasks.findById(taskId).orElseThrow().getComplaint().getId();
    }

    /**
     * Resolves the task (T14).
     *
     * <p>If the credit is already on the ledger, this is a repeated tap (e.g. after lost signal): it
     * returns success without moving the complaint again, which would otherwise be refused and would
     * restart the 7-day window.
     */
    public ResolvedTask resolve(long taskId) {
        TaskAssignment mine = myAssignment(taskId);
        Task task = mine.getTask();
        Complaint complaint = task.getComplaint();
        if (!complaint.hasDocket()) {
            // The table refuses this too — IN_PROGRESS is only reachable through T11, so a
            // complaint that can be resolved always has a docket. Saying so here is what turns a
            // stale screen's "you cannot resolve from ASSIGNED" into "file the docket first".
            throw new DocketRequiredException(taskId);
        }

        boolean replay =
                ledger.alreadyCreditedForResolve(
                        mine.getAttenderId(), task.getId(), complaint.getReopenCount());
        if (!replay) {
            workflow.transition(
                    complaint.getId(), WorkflowEvent.MARK_RESOLVED, TransitionPayload.none());
        }

        return new ResolvedTask(
                complaint.getPublicRef(),
                replay ? 0 : task.getPointValue(),
                ledger.myPeriodTotal(),
                replay);
    }

    /**
     * The caller's active assignment on this task, or {@code AccessDeniedException} (a 403) if they do not
     * hold it.
     */
    private TaskAssignment myAssignment(long taskId) {
        long attenderId =
                CurrentUser.get()
                        .map(SignedInUser::getUserId)
                        .orElseThrow(() -> new AccessDeniedException("nobody is signed in"));
        return assignments
                .findByTaskIdAndAttenderId(taskId, attenderId)
                .filter(assignment -> assignment.getStatus() != AssignmentStatus.RELEASED)
                .orElseThrow(() -> new AccessDeniedException("this task is not yours to resolve"));
    }

    /**
     * What the attender is shown after tapping Resolve.
     *
     * @param pointsAwarded points credited by this call; {@code 0} on a repeated tap
     * @param periodTotal their total for the month afterwards
     * @param alreadyApplied {@code true} on a repeated tap; the screen looks the same either way
     */
    public record ResolvedTask(
            String publicRef, int pointsAwarded, long periodTotal, boolean alreadyApplied) {}
}
