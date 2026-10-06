package duty.workflow.domain;

/** The life of one attender's hold on a task (data model section 3.6). */
public enum AssignmentStatus {

    /** The attender currently owns the work. */
    ACTIVE,

    /** Handed back, or superseded by a reassignment. */
    RELEASED,

    /** The attender marked their part done; the credit is keyed to this pair. */
    COMPLETED
}
