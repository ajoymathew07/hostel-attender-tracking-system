package duty.identity.application;

import org.springframework.stereotype.Component;

/**
 * The {@code @access} bean used in {@code @PreAuthorize("@access.ownsTask(#taskId)")}.
 *
 * <p>Why: a role check alone would let any attender act on another attender's task and collect the
 * points. Checking on the service means every caller gets the check, not just one controller.
 */
@Component("access")
public class AccessGuard {

    private final TaskAssignmentLookup assignments;

    public AccessGuard(TaskAssignmentLookup assignments) {
        this.assignments = assignments;
    }

    /** Whether the signed-in user holds an {@code ACTIVE} assignment for this task (E20). */
    public boolean ownsTask(long taskId) {
        return CurrentUser.get()
                .map(user -> assignments.holdsActiveAssignment(user.getUserId(), taskId))
                .orElse(false);
    }

    /**
     * Whether a user holds an {@code ACTIVE} assignment for a task.
     *
     * <p>Declared here and implemented in {@code workflow}, because assignments are workflow's table and
     * no other module may read it.
     */
    @FunctionalInterface
    public interface TaskAssignmentLookup {

        boolean holdsActiveAssignment(long userId, long taskId);
    }
}
