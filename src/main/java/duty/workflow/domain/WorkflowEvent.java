package duty.workflow.domain;

/**
 * Everything that can happen to an existing complaint. The event says what was asked for; the
 * transition table decides the resulting state.
 *
 * <p>Creating a complaint (T1) is not here, because it has no prior state; {@code ComplaintIntake}
 * handles it.
 */
public enum WorkflowEvent {

    /** T2/T3: auto-dispatch ran; its result decides between {@code ASSIGNED} and {@code TRIAGE}. */
    AUTO_DISPATCH,

    /** T4 — the manager picks a name. */
    MANAGER_ASSIGN,

    /** T5 — not a real complaint, or not ours. */
    MANAGER_REJECT,

    /** T6 — the manager moves live work to somebody else. */
    MANAGER_REASSIGN,

    /** T7 — the attender is on their way. */
    ATTENDER_START,

    /** T8 — the attender hands the work back. */
    ATTENDER_RELEASE,

    /** T9 — there is a defect, and the Engineering Unit needs a docket for it. */
    DEFECT_CONFIRMED,

    /** T10 — nothing wrong on site. */
    NO_DEFECT_FOUND,

    /** T11 — the number the Engineering Unit gave back. */
    DOCKET_RECORDED,

    /** T12 — waiting on parts or on access. */
    BLOCK,

    /** T13 — the block cleared. */
    UNBLOCK,

    /** T14 — the work is done and the +2 is due. */
    MARK_RESOLVED,

    /** T15 — the manager signs it off. */
    MANAGER_CLOSE,

    /** T16 — nobody signed it off and seven days passed. */
    AUTO_CLOSE,

    /** T17 — it was not fixed after all; the credit is reversed. */
    MANAGER_REOPEN
}
