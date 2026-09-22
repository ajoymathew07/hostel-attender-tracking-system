package in.ac.iitm.hostel.duty.workflow.domain;

/**
 * Matches {@code ck_task_type} and, with it, {@code ck_task_link}: a {@code COMPLAINT} task must
 * carry a complaint and a {@code SPECIAL_DUTY} must not (data model section 3.6).
 */
public enum TaskType {

    /** Work derived from a resident complaint. Credits +2 by default. */
    COMPLAINT,

    /** A posted duty with no complaint and no docket — claimed rather than dispatched. */
    SPECIAL_DUTY
}
