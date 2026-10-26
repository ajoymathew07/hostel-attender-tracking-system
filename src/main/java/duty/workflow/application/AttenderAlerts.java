package duty.workflow.application;

import duty.workflow.domain.Complaint.Priority;
import duty.workflow.domain.Complaint;
import duty.workflow.domain.Task;
import duty.workflow.domain.TaskAssignment.AssignmentStatus;
import duty.workflow.infrastructure.TaskAssignmentRepository;
import duty.workflow.infrastructure.TaskRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Announces the two moments an attender must hear about away from the app: work given to them, and
 * their work becoming urgent (section 9.6).
 *
 * <p>Only an event is published here. The notification module sends it after commit, so a rolled-back
 * assignment never reaches a phone.
 */
@Component
public class AttenderAlerts {

    private final ApplicationEventPublisher events;
    private final TaskRepository tasks;
    private final TaskAssignmentRepository assignments;

    AttenderAlerts(ApplicationEventPublisher events, TaskRepository tasks, TaskAssignmentRepository assignments) {
        this.events = events;
        this.tasks = tasks;
        this.assignments = assignments;
    }

    void assigned(Complaint complaint, Task task, long attenderId) {
        events.publishEvent(alert(complaint, task, attenderId, false));
    }

    /** Tells everyone holding the complaint's work that it is now P1. */
    void escalated(Complaint complaint) {
        if (complaint.getPriority() != Priority.P1) {
            return;
        }
        tasks.findByComplaintId(complaint.getId())
                .ifPresent(
                        task ->
                                assignments
                                        .findByTaskIdAndStatus(task.getId(), AssignmentStatus.ACTIVE)
                                        .forEach(
                                                held ->
                                                        events.publishEvent(
                                                                alert(complaint, task, held.getAttenderId(), true))));
    }

    private static AttenderAlert alert(Complaint complaint, Task task, long attenderId, boolean escalated) {
        return new AttenderAlert(
                attenderId,
                task.getId(),
                complaint.getPublicRef(),
                complaint.getReporterRoom(),
                complaint.getDomain().name(),
                complaint.getPriority() == Priority.P1,
                escalated);
    }

    /**
     * What a push may say: the reference, the room and the domain. Never the resident's text or details,
     * because the push service and the lock screen are outside our control (section 11.4).
     */
    public record AttenderAlert(
            long attenderId,
            long taskId,
            String publicRef,
            String room,
            String domain,
            boolean urgent,
            boolean escalated) {}
}
