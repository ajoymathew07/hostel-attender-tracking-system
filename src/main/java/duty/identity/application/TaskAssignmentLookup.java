package duty.identity.application;

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
