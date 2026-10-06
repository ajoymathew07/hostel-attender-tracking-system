package duty.workflow.domain;

/** Matches {@code ck_asg_method} (data model section 3.6). */
public enum AssignMethod {

    /** Chosen by the dispatch heuristic; {@code score_snapshot} records why. */
    AUTO,

    /** A manager assigned or reassigned it. */
    MANUAL,

    /** An attender claimed a posted special duty (section 7.6). */
    CLAIMED
}
