package duty.workflow.domain;

/** The task lifecycle states. */
public enum TaskStatus {
    OPEN,
    CLAIMED,
    COMPLETED,
    CANCELLED,
    EXPIRED
}
