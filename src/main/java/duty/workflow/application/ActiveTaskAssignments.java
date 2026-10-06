package duty.workflow.application;

import duty.identity.application.TaskAssignmentLookup;
import duty.workflow.domain.TaskAssignment;
import duty.workflow.infrastructure.TaskAssignmentRepository;
import org.springframework.stereotype.Component;

/**
 * The answer to {@code @access.ownsTask} (section 11.3).
 *
 * <p>{@code task_assignment} is this module's table, and no other module may reach its
 * repositories (architecture section 2.4, rule 3). The identity module therefore declares the
 * question as a port and {@code workflow} answers it here.
 *
 * <p>{@code uq_task_attender} makes the lookup single-valued, so it is one indexed read.
 */
@Component
class ActiveTaskAssignments implements TaskAssignmentLookup {

    private final TaskAssignmentRepository assignments;

    ActiveTaskAssignments(TaskAssignmentRepository assignments) {
        this.assignments = assignments;
    }

    /**
     * {@code ACTIVE} and nothing else. A {@code RELEASED} row records that this attender once held
     * the task, which is exactly the person who must no longer act on it (E20, {@code
     * NOT_ASSIGNED}).
     */
    @Override
    public boolean holdsActiveAssignment(long userId, long taskId) {
        return assignments
                .findByTaskIdAndAttenderId(taskId, userId)
                .filter(TaskAssignment::isActive)
                .isPresent();
    }
}
