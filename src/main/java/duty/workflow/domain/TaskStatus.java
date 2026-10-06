package duty.workflow.domain;

/**
 * The task lifecycle of state machines section 4.4.
 *
 * <p>Like {@code complaint.status}, the column carries no CHECK constraint — the legal set is the
 * transition table, enforced by the workflow module as the only write path.
 */
public enum TaskStatus {
    OPEN,
    CLAIMED,
    COMPLETED,
    CANCELLED,
    EXPIRED
}
