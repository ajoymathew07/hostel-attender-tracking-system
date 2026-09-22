package in.ac.iitm.hostel.duty.workflow.application;

import in.ac.iitm.hostel.duty.audit.application.AuditDetail;
import in.ac.iitm.hostel.duty.audit.application.AuditEntry;
import in.ac.iitm.hostel.duty.audit.application.AuditTrail;
import in.ac.iitm.hostel.duty.identity.application.CurrentUser;
import in.ac.iitm.hostel.duty.workflow.domain.AssignMethod;
import in.ac.iitm.hostel.duty.workflow.domain.AssignmentStatus;
import in.ac.iitm.hostel.duty.workflow.domain.Complaint;
import in.ac.iitm.hostel.duty.workflow.domain.Task;
import in.ac.iitm.hostel.duty.workflow.domain.TaskAssignment;
import in.ac.iitm.hostel.duty.workflow.infrastructure.ComplaintRepository;
import in.ac.iitm.hostel.duty.workflow.infrastructure.TaskAssignmentRepository;
import in.ac.iitm.hostel.duty.workflow.infrastructure.TaskRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manual assignment from the manager's desktop: assign, reassign, release (section 10.3).
 *
 * <p>Manager-only, and the class-level {@code @PreAuthorize} is where section 11.3 requires it —
 * on the service. "Classify, assign, reassign, reject, reopen" is a manager capability, and an
 * attender who reaches this bean by any route at all is refused here rather than in whichever
 * controller happened to be in front of it.
 *
 * <p><strong>Complaint status is deliberately untouched.</strong> The transition table of section
 * 4.2 moves a complaint to {@code ASSIGNED} on T4/T6 along with its guards, its audit and its
 * notifications, and that table is PR 11's. Writing half of it here would be a second write path
 * to {@code complaint.status}, which section 4.1 says must not exist. What this PR owns is the
 * assignment itself.
 */
@Service
@Transactional
@PreAuthorize("hasRole('MANAGER')")
public class ManualAssignmentService {

    private static final String ENTITY_TYPE = "task";

    private final ComplaintRepository complaints;
    private final TaskRepository tasks;
    private final TaskAssignmentRepository assignments;
    private final AuditTrail audit;

    ManualAssignmentService(
            ComplaintRepository complaints,
            TaskRepository tasks,
            TaskAssignmentRepository assignments,
            AuditTrail audit) {
        this.complaints = complaints;
        this.tasks = tasks;
        this.assignments = assignments;
        this.audit = audit;
    }

    /**
     * Gives this complaint's work to an attender, taking it off whoever held it (T4, T6).
     *
     * <p>The same method is assign and reassign, because from the manager's side they are the same
     * gesture — pick a name, press the button — and the difference is only whether somebody held
     * it already. Two methods would be two code paths for one screen control.
     */
    public void assign(long complaintId, long attenderId) {
        Instant now = Instant.now();
        Task task = taskFor(complaintId, now);

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
    }

    /** Hands the work back to the pool, leaving the task for the next assignment (T8's effect). */
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
     * The complaint's task, created on first assignment.
     *
     * <p>Section 4.2 creates the task as a side effect of T2/T4, so before anybody is assigned
     * there is nothing to assign. Creating it here on demand keeps that true without a nightly job
     * manufacturing tasks nobody will ever hold.
     */
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
     * The reference and the room, never the resident's words or details.
     *
     * <p>{@code task.title} is shown on leaderboards and in notifications, neither of which is
     * covered by the 7-day window, so the complaint's text does not get copied into it (section
     * 11.4, section 11.6).
     */
    private static String title(Complaint complaint) {
        return complaint.getReporterRoom() == null
                ? complaint.getPublicRef()
                : complaint.getPublicRef() + " · Room " + complaint.getReporterRoom();
    }

    /** The detail names ids, not people: audit rows are kept indefinitely (section 11.4). */
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
