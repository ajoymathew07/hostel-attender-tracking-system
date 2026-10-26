package duty.workflow.application;

import duty.audit.application.AuditTrail.AuditDetail;
import duty.audit.application.AuditTrail.AuditEntry;
import duty.audit.application.AuditTrail;
import duty.dispatch.application.AttenderDispatcher.DispatchResult;
import duty.dispatch.application.AttenderDispatcher;
import duty.workflow.application.WorkflowService.TransitionPayload;
import duty.workflow.domain.Complaint;
import duty.workflow.domain.ComplaintTransitions.WorkflowEvent;
import duty.workflow.domain.Task;
import duty.workflow.domain.TaskAssignment.AssignMethod;
import duty.workflow.domain.TaskAssignment.AssignmentStatus;
import duty.workflow.domain.TaskAssignment;
import duty.workflow.infrastructure.TaskAssignmentRepository;
import duty.workflow.infrastructure.TaskRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Auto-dispatch for a new complaint: assign it to an attender (T2) or send it to triage with a reason
 * (T3). There is no third outcome.
 *
 * <p>Triage reasons: no usable domain ("Others" or blank), an unreadable room number, or nobody
 * available. The status change goes through {@link WorkflowService}; this class creates the task, the
 * {@code AUTO} assignment and the score snapshot.
 */
@Service
@Transactional
public class AutoDispatchService {

    private static final String ENTITY_TYPE = "complaint";

    /** Triage reasons, one per case. */
    private static final String UNCLASSIFIED =
            "the resident did not say which kind of problem this is";

    private static final String UNMAPPED_LOCATION =
            "the room number could not be mapped to a floor, so dispatch had nowhere to send it";

    private static final String NO_AVAILABLE_ATTENDER =
            "no attender was available for this complaint";

    private final AttenderDispatcher dispatcher;
    private final WorkflowService workflow;
    private final TaskRepository tasks;
    private final TaskAssignmentRepository assignments;
    private final AuditTrail audit;
    private final AttenderAlerts alerts;

    AutoDispatchService(
            AttenderDispatcher dispatcher,
            WorkflowService workflow,
            TaskRepository tasks,
            TaskAssignmentRepository assignments,
            AuditTrail audit,
            AttenderAlerts alerts) {
        this.dispatcher = dispatcher;
        this.workflow = workflow;
        this.tasks = tasks;
        this.assignments = assignments;
        this.audit = audit;
        this.alerts = alerts;
    }

    /** Dispatches a newly ingested complaint, or sends it to triage with the reason. */
    public void dispatch(Complaint complaint) {
        if (!complaint.getDomain().isDispatchable()) {
            triage(complaint, UNCLASSIFIED);
            return;
        }
        if (complaint.getFloorId() == null) {
            triage(complaint, UNMAPPED_LOCATION);
            return;
        }

        Optional<DispatchResult> chosen =
                dispatcher.choose(complaint.getFloorId(), alreadyAssigned(complaint));
        if (chosen.isEmpty()) {
            triage(complaint, NO_AVAILABLE_ATTENDER);
            return;
        }
        assign(complaint, chosen.get());
    }

    /** Sends the complaint to triage (T3). */
    private void triage(Complaint complaint, String reason) {
        workflow.transition(complaint.getId(), WorkflowEvent.AUTO_DISPATCH, TransitionPayload.reason(reason));
    }

    /**
     * Creates the task and {@code AUTO} assignment with its snapshot, then moves the complaint to
     * {@code ASSIGNED} (T2). Rows first, so an {@code ASSIGNED} complaint always has a holder.
     */
    private void assign(Complaint complaint, DispatchResult result) {
        Instant now = Instant.now();
        Task task =
                tasks.findByComplaintId(complaint.getId())
                        .orElseGet(
                                () ->
                                        tasks.saveAndFlush(
                                                Task.forComplaint(
                                                        complaint,
                                                        title(complaint),
                                                        Task.DEFAULT_COMPLAINT_POINTS,
                                                        null,
                                                        now)));

        TaskAssignment assignment =
                new TaskAssignment(task, result.attenderId(), AssignMethod.AUTO, null, now);
        assignment.recordScoreSnapshot(result.scoreSnapshot());
        assignments.saveAndFlush(assignment);

        workflow.transition(
                complaint.getId(),
                WorkflowEvent.AUTO_DISPATCH,
                TransitionPayload.attender(result.attenderId()));
        alerts.assigned(complaint, task, result.attenderId());

        warnIfStretched(complaint, task, result);
    }

    /**
     * Assigns anyway when everyone is full or nobody covers the floor, but records it in the audit log so
     * the manager can see it.
     */
    private void warnIfStretched(Complaint complaint, Task task, DispatchResult result) {
        if (!result.capacityExceeded() && !result.uncoveredFloor()) {
            return;
        }
        audit.record(
                AuditEntry.change(
                        ENTITY_TYPE,
                        complaint.getId(),
                        "DISPATCH_STRETCHED",
                        AuditDetail.of("taskId", task.getId())
                                .and("attenderId", result.attenderId())
                                .and("capacityExceeded", result.capacityExceeded())
                                .and("uncoveredFloor", result.uncoveredFloor())
                                .json(),
                        null,
                        null));
    }

    /** Who already holds this complaint's task, and so cannot be given it again. */
    private Set<Long> alreadyAssigned(Complaint complaint) {
        return tasks.findByComplaintId(complaint.getId())
                .map(
                        task ->
                                assignments.findByTaskIdAndStatus(task.getId(), AssignmentStatus.ACTIVE).stream()
                                        .map(TaskAssignment::getAttenderId)
                                        .collect(Collectors.toSet()))
                .orElseGet(Set::of);
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
}
