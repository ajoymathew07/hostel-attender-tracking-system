package duty.workflow.application;

import duty.identity.application.TaskAssignmentLookup;
import duty.workflow.domain.TaskAssignment;
import duty.workflow.infrastructure.TaskAssignmentRepository;
import org.springframework.stereotype.Component;

/**
 * Implements {@code @access.ownsTask}: does this user hold an active assignment for the task? It lives
 * here because assignments are this module's table.
 */
@Component
class ActiveTaskAssignments implements TaskAssignmentLookup {

    private final TaskAssignmentRepository assignments;

    ActiveTaskAssignments(TaskAssignmentRepository assignments) {
        this.assignments = assignments;
    }

    /**
     * Only {@code ACTIVE} counts. A {@code RELEASED} row means the attender used to hold the task and may
     * no longer act on it.
     */
    @Override
    public boolean holdsActiveAssignment(long userId, long taskId) {
        return assignments
                .findByTaskIdAndAttenderId(taskId, userId)
                .filter(TaskAssignment::isActive)
                .isPresent();
    }
}
