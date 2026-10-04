package in.ac.iitm.hostel.duty.workflow.application;

import in.ac.iitm.hostel.duty.audit.application.AuditDetail;
import in.ac.iitm.hostel.duty.audit.application.AuditEntry;
import in.ac.iitm.hostel.duty.audit.application.AuditTrail;
import in.ac.iitm.hostel.duty.dispatch.application.AttenderDispatcher;
import in.ac.iitm.hostel.duty.dispatch.application.DispatchResult;
import in.ac.iitm.hostel.duty.workflow.domain.AssignMethod;
import in.ac.iitm.hostel.duty.workflow.domain.AssignmentStatus;
import in.ac.iitm.hostel.duty.workflow.domain.Complaint;
import in.ac.iitm.hostel.duty.workflow.domain.Task;
import in.ac.iitm.hostel.duty.workflow.domain.TaskAssignment;
import in.ac.iitm.hostel.duty.workflow.domain.WorkflowEvent;
import in.ac.iitm.hostel.duty.workflow.infrastructure.TaskAssignmentRepository;
import in.ac.iitm.hostel.duty.workflow.infrastructure.TaskRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <strong>Transitions T2 and T3 — the dispatcher's fork</strong> (section 4.2).
 *
 * <p>Every complaint leaves intake down one of two paths and there is no third: somebody is given
 * the work ({@code ASSIGNED}), or the complaint goes to {@code TRIAGE} with a recorded reason. That
 * is requirement R1 of section 6.1, and it is why {@code NEW} is a state a manager never sees.
 *
 * <p>Three reasons send a complaint to triage, and each is a row of section 6.4's table:
 *
 * <ul>
 *   <li>{@code UNCLASSIFIED} — the resident answered "Others" or nothing, so there is no domain to dispatch
 *       on and the heuristic is never consulted;
 *   <li>{@code UNMAPPED_LOCATION} — the room number is unreadable, and dispatching on a location we
 *       could not read is worse than asking;
 *   <li>{@code NO_AVAILABLE_ATTENDER} — {@code E = ∅}; everyone is on leave or off duty.
 * </ul>
 *
 * <p>The complaint's state still moves through {@link WorkflowService} and nowhere else (section
 * 4.1). What this class owns is T2's other side effects: the task, the {@code AUTO} assignment row,
 * and the score snapshot that answers "why them" months later.
 */
@Service
@Transactional
public class AutoDispatchService {

    private static final String ENTITY_TYPE = "complaint";

    /** T3's "record triage reason", one sentence per row of section 6.4. */
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

    AutoDispatchService(
            AttenderDispatcher dispatcher,
            WorkflowService workflow,
            TaskRepository tasks,
            TaskAssignmentRepository assignments,
            AuditTrail audit) {
        this.dispatcher = dispatcher;
        this.workflow = workflow;
        this.tasks = tasks;
        this.assignments = assignments;
        this.audit = audit;
    }

    /** Dispatches a freshly ingested complaint, or files it for the manager with the reason. */
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

    /** T3. */
    private void triage(Complaint complaint, String reason) {
        workflow.transition(complaint.getId(), WorkflowEvent.AUTO_DISPATCH, TransitionPayload.reason(reason));
    }

    /**
     * T2 — the task, the {@code AUTO} assignment and the snapshot, then the state move.
     *
     * <p>The rows are written before the transition because T2's guard is "dispatch returned a
     * candidate", which the payload carries, and because the state move is the commit point: a
     * complaint that reads {@code ASSIGNED} with nobody holding it is the one outcome section 6.4
     * says must not exist.
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

        warnIfStretched(complaint, task, result);
    }

    /**
     * Section 6.4's two "assign anyway, but say so" rows.
     *
     * <p>Overflowing visibly beats triaging silently — work cannot vanish because everybody is
     * busy — but a manager who is never told that every attender is at capacity, or that nobody
     * covers a floor at all, cannot act on either. The audit row is where the dashboard reads it.
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

    /** Who already holds this complaint's task, and so cannot be handed it again. */
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
     * The reference and the room, never the resident's words or details — the same rule the
     * manual path follows, for the same reason: {@code task.title} appears on leaderboards and in
     * notifications, neither of which is covered by the 7-day window (section 11.4).
     */
    private static String title(Complaint complaint) {
        return complaint.getReporterRoom() == null
                ? complaint.getPublicRef()
                : complaint.getPublicRef() + " · Room " + complaint.getReporterRoom();
    }
}
