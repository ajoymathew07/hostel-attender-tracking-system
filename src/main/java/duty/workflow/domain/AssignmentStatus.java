package duty.workflow.domain;

/** State of one attender's hold on a task. */
public enum AssignmentStatus {

    /** The attender currently owns the work. */
    ACTIVE,

    /** Handed back, or superseded by a reassignment. */
    RELEASED,

    /** The attender finished their part. */
    COMPLETED
}
