package duty.identity.application;

/**
 * Whether a user currently holds an {@code ACTIVE} assignment for a task.
 *
 * <p>The ownership half of section 11.3 lives in {@code workflow} — {@code task_assignment} is that
 * module's table, and no other module may reach its repositories (architecture section 2.4, rule
 * 3). So the check is declared here, as a port the {@code workflow} module implements, and the
 * security expression stays in one place instead of every task service repeating the query.
 */
@FunctionalInterface
public interface TaskAssignmentLookup {

    boolean holdsActiveAssignment(long userId, long taskId);
}
