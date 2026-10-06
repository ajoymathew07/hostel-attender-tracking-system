package duty.workflow.domain;

/** How an assignment was made. Matches {@code ck_asg_method}. */
public enum AssignMethod {

    /** Chosen by the dispatch heuristic; {@code score_snapshot} records why. */
    AUTO,

    /** A manager assigned or reassigned it. */
    MANUAL,

    /** An attender claimed a posted special duty. */
    CLAIMED
}
