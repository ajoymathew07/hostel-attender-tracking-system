package duty.workflow.application;

import duty.audit.application.AuditTrail.AuditDetail;
import duty.audit.application.AuditTrail.AuditEntry;
import duty.audit.application.AuditTrail;
import duty.identity.application.CurrentUser;
import duty.workflow.application.WorkflowService.GuardFailedException;
import duty.workflow.application.WorkflowService.TransitionPayload;
import duty.workflow.domain.Complaint.ComplaintStatus;
import duty.workflow.domain.Complaint;
import duty.workflow.domain.ComplaintTransitions.WorkflowEvent;
import duty.workflow.domain.Task;
import duty.workflow.domain.TaskAssignment.AssignMethod;
import duty.workflow.domain.TaskAssignment.AssignmentStatus;
import duty.workflow.domain.TaskAssignment;
import duty.workflow.infrastructure.ComplaintRepository;
import duty.workflow.infrastructure.TaskAssignmentRepository;
import duty.workflow.infrastructure.TaskRepository;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manual assignment from the manager's dashboard: assign, reassign, release. Manager-only.
 *
 * <p>This class manages the assignment rows; status changes go through {@link WorkflowService}.
 */
@Service
@Transactional
@PreAuthorize("hasRole('MANAGER')")
public class ManualAssignmentService {

    private static final String ENTITY_TYPE = "task";

    /** Finished work cannot be handed to anyone; it has to be reopened first. */
    private static final Set<ComplaintStatus> FINISHED =
            EnumSet.of(ComplaintStatus.RESOLVED, ComplaintStatus.CLOSED, ComplaintStatus.REJECTED);

    private final ComplaintRepository complaints;
    private final TaskRepository tasks;
    private final TaskAssignmentRepository assignments;
    private final AuditTrail audit;
    private final WorkflowService workflow;
    private final AttenderAlerts alerts;

    ManualAssignmentService(
            ComplaintRepository complaints,
            TaskRepository tasks,
            TaskAssignmentRepository assignments,
            AuditTrail audit,
            WorkflowService workflow,
            AttenderAlerts alerts) {
        this.complaints = complaints;
        this.tasks = tasks;
        this.assignments = assignments;
        this.audit = audit;
        this.workflow = workflow;
        this.alerts = alerts;
    }

    /**
     * Gives the complaint's work to an attender, taking it off whoever held it. One method for assign and
     * reassign, since the screen has one control for both.
     */
    public void assign(long complaintId, long attenderId) {
        Complaint complaint = complaints.findById(complaintId).orElseThrow();
        ComplaintStatus status = complaint.getStatus();
        if (FINISHED.contains(status)) {
            throw new GuardFailedException("this complaint is " + status + "; reopen it before assigning it");
        }
        Instant now = Instant.now();
        Task task = taskFor(complaintId, now);

        // Before the rows move, so T6's guard can still see who holds the work.
        moveComplaint(complaintId, attenderId);

        // Whoever else holds it loses it. `uq_task_attender` is what makes this safe to express as
        // "release the others, then take the row for this one".
        List<TaskAssignment> held = assignments.findByTaskIdAndStatus(task.getId(), AssignmentStatus.ACTIVE);
        for (TaskAssignment previous : held) {
            if (!previous.getAttenderId().equals(attenderId)) {
                previous.release();
                record(task, "ASSIGNMENT_RELEASED", previous.getAttenderId(), complaintId);
            }
        }

        TaskAssignment assignment =
                assignments
                        .findByTaskIdAndAttenderId(task.getId(), attenderId)
                        .map(
                                existing -> {
                                    existing.assignAgain(CurrentUser.idOrNull(), now);
                                    return existing;
                                })
                        .orElseGet(
                                () ->
                                        new TaskAssignment(
                                                task,
                                                attenderId,
                                                AssignMethod.MANUAL,
                                                CurrentUser.idOrNull(),
                                                now));
        assignments.save(assignment);
        record(task, "TASK_ASSIGNED", attenderId, complaintId);
        alerts.assigned(complaint, task, attenderId);
    }

    /** Hands the work back to the pool; the task stays for the next assignment. */
    public void release(long complaintId) {
        tasks.findByComplaintId(complaintId)
                .ifPresent(
                        task ->
                                assignments
                                        .findByTaskIdAndStatus(task.getId(), AssignmentStatus.ACTIVE)
                                        .forEach(
                                                assignment -> {
                                                    assignment.release();
                                                    record(
                                                            task,
                                                            "ASSIGNMENT_RELEASED",
                                                            assignment.getAttenderId(),
                                                            complaintId);
                                                }));
    }

    /**
     * Moves the complaint's status if needed: from triage it becomes {@code ASSIGNED} (T4); if someone else
     * held it, it is reassigned (T6); if the same attender already holds it, nothing changes. Once work has
     * started the status stays where it is.
     */
    private void moveComplaint(long complaintId, long attenderId) {
        Complaint complaint = complaints.findById(complaintId).orElseThrow();
        TransitionPayload target = TransitionPayload.attender(attenderId);
        switch (complaint.getStatus()) {
            case TRIAGE -> workflow.transition(complaintId, WorkflowEvent.MANAGER_ASSIGN, target);
            case ASSIGNED -> {
                if (!alreadyHolds(complaintId, attenderId)) {
                    workflow.transition(complaintId, WorkflowEvent.MANAGER_REASSIGN, target);
                }
            }
            default -> {
                // Work has started; the handover is the assignment's business, not the machine's.
            }
        }
    }

    private boolean alreadyHolds(long complaintId, long attenderId) {
        return tasks.findByComplaintId(complaintId)
                .map(
                        task ->
                                assignments
                                        .findByTaskIdAndStatus(task.getId(), AssignmentStatus.ACTIVE)
                                        .stream()
                                        .anyMatch(held -> held.getAttenderId().equals(attenderId)))
                .orElse(false);
    }

    /** The complaint's task, created on first assignment. */
    private Task taskFor(long complaintId, Instant now) {
        return tasks.findByComplaintId(complaintId)
                .orElseGet(
                        () -> {
                            Complaint complaint = complaints.findById(complaintId).orElseThrow();
                            return tasks.saveAndFlush(
                                    Task.forComplaint(
                                            complaint,
                                            title(complaint),
                                            Task.DEFAULT_COMPLAINT_POINTS,
                                            CurrentUser.idOrNull(),
                                            now));
                        });
    }

    /**
     * The reference and room only, never the resident's text or details, because task titles are shown
     * widely.
     */
    private static String title(Complaint complaint) {
        return complaint.getReporterRoom() == null
                ? complaint.getPublicRef()
                : complaint.getPublicRef() + " · Room " + complaint.getReporterRoom();
    }

    /** Audits the assignment by ids only, since audit rows are kept forever. */
    private void record(Task task, String eventType, Long attenderId, long complaintId) {
        audit.record(
                AuditEntry.change(
                        ENTITY_TYPE,
                        task.getId(),
                        eventType,
                        AuditDetail.of("complaintId", complaintId).and("attenderId", attenderId).json(),
                        CurrentUser.idOrNull(),
                        CurrentUser.roleOrNull()));
    }
}
