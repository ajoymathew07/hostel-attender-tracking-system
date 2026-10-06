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
}
