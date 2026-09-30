package in.ac.iitm.hostel.duty.workflow.domain;

/**
 * Everything that can happen to a complaint (state machines section 4.2).
 *
 * <p>An event is what the actor <em>asks for</em>, never where the complaint ends up. Which state
 * it lands in is the transition table's answer, and for {@code AUTO_DISPATCH} that answer depends
 * on the guard — which is exactly why the table is data and this enum is only its vocabulary.
 *
 * <p>{@code ingest} (T1) is deliberately absent. Every other row moves a complaint that already
 * exists; T1 <em>creates</em> one, so it has no source state to look up and no aggregate to load.
 * It stays where it is, in the ingestion module's intake.
 */
public enum WorkflowEvent {

    /** T2/T3 — the dispatcher had a go; the guard decides between ASSIGNED and TRIAGE. */
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
