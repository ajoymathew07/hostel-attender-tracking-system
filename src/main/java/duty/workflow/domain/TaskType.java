package duty.workflow.domain;

/** Kind of task. Matches {@code ck_task_type}. */
public enum TaskType {

    /** Work derived from a resident complaint. Credits +2 by default. */
    COMPLAINT,

    /** A posted duty with no complaint, claimed rather than dispatched. */
    SPECIAL_DUTY
}
