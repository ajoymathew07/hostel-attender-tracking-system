package duty.workflow.application;

import duty.identity.application.CurrentUser;
import duty.identity.application.SignedInUser;
import duty.ledger.application.PointsLedger;
import duty.workflow.domain.AssignmentStatus;
import duty.workflow.domain.Complaint;
import duty.workflow.domain.Task;
import duty.workflow.domain.TaskAssignment;
import duty.workflow.domain.WorkflowEvent;
import duty.workflow.infrastructure.TaskAssignmentRepository;
import duty.workflow.infrastructure.TaskRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The attender's walk through the machine, from their phone: start (T7), confirm or rule out a
 * defect (T9, T10), file the docket (T11) and resolve (T14).
 *
 * <p><strong>Neither writes {@code complaint.status} itself any more.</strong> Both hand the event
 * to {@link WorkflowService}, which owns the transition table — section 4.1 is explicit that there
 * is no second write path. What is left here is the part that is genuinely about the phone: which
 * task the caller holds, and what the screen is told afterwards.
 *
 * <p><strong>Ownership, not role.</strong> Neither action has a {@code hasRole('ATTENDER')} on it:
 * "any attender" is precisely the wrong answer, because it would let one attender resolve
 * another's work and collect the +2 (section 11.3). {@link #recordDocket} uses the existing
 * {@code @access.ownsTask}; {@link #resolve} cannot, because it has to load that same assignment
 * to find out who to credit, and it must accept the caller's own already-{@code COMPLETED} row as
 * a replay rather than refuse it (section 7.4). The transition table checks the same ownership
 * again from its Actor column, which is where a future caller inherits it.
 */
@Service
@Transactional
public class TaskResolutionService {

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

    /** Transition T7 — the attender is on their way to the room. */
    @PreAuthorize("@access.ownsTask(#taskId)")
    public void startInspection(long taskId) {
        workflow.transition(
                complaintIdOf(taskId), WorkflowEvent.ATTENDER_START, TransitionPayload.none());
    }

    /** Transition T9 — there is a defect, and the Engineering Unit needs a docket for it. */
    @PreAuthorize("@access.ownsTask(#taskId)")
    public void confirmDefect(long taskId) {
        workflow.transition(
                complaintIdOf(taskId), WorkflowEvent.DEFECT_CONFIRMED, TransitionPayload.none());
    }

    /**
     * Transition T10 — nothing wrong on site, so the complaint is closed and <strong>no points are
     * credited</strong>. The note is the table's guard, not politeness: this is the one way an
     * attender can close work without earning, so the record has to say why.
     */
    @PreAuthorize("@access.ownsTask(#taskId)")
    public void reportNoDefect(long taskId, String note) {
        workflow.transition(
                complaintIdOf(taskId), WorkflowEvent.NO_DEFECT_FOUND, TransitionPayload.reason(note));
    }

    /**
     * Transition T11 — the attender files the complaint with the Engineering Unit and types the
     * number back in.
     *
     * <p>A correction overwrites the number in place and the audit row keeps both values, which is
     * why there is no docket history table (section 4.3, ADR-012). The format check is the table's
     * guard, not this method's: one docket rule, in one place.
     */
    @PreAuthorize("@access.ownsTask(#taskId)")
    public void recordDocket(long taskId, String docketNo) {
        workflow.transition(
                complaintIdOf(taskId), WorkflowEvent.DOCKET_RECORDED, TransitionPayload.docket(docketNo));
    }

    /** The attender's world is tasks; the machine's is complaints. This is the one join. */
    private long complaintIdOf(long taskId) {
        return tasks.findById(taskId).orElseThrow().getComplaint().getId();
    }

    /**
     * Transition T14 — the work is done.
     *
     * <p>The ledger decides whether there is anything to do. If the credit for this attempt is
     * already there, this is a replay of the same tap — the attender lost signal and the service
     * worker sent it again (section 7.4) — so no transition is attempted at all: the complaint is
     * already {@code RESOLVED}, and asking the table to move it again would be an
     * {@link IllegalTransitionException} where the attender deserves the same success screen.
     * Re-stamping {@code resolved_at} would also silently restart the 7-day window on the
     * resident's contact details (section 11.4).
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
     * The caller's own hold on this task.
     *
     * <p>A {@code RELEASED} row records that this attender once held the task, which is exactly
     * the person who must no longer act on it (E20). An absent row is a stranger. Both are
     * {@code AccessDeniedException}, so the refusal reaches the browser as the same 403 that
     * {@code @access.ownsTask} produces, with no handler of its own.
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
}
