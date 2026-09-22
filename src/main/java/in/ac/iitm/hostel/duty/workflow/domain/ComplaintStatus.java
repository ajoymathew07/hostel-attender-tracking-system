package in.ac.iitm.hostel.duty.workflow.domain;

/**
 * The complaint lifecycle of state machines section 4.2.
 *
 * <p>The column carries no CHECK constraint: the legal set is the transition table, which the
 * workflow module enforces as the only write path (PR 11). This enum is the vocabulary.
 */
public enum ComplaintStatus {
    NEW,
    TRIAGE,
    ASSIGNED,
    INSPECTING,
    AWAITING_CMS,
    IN_PROGRESS,
    ON_HOLD,
    RESOLVED,
    CLOSED,
    REJECTED;

    /** Terminal states run no further transitions. */
    public boolean isTerminal() {
        return this == CLOSED || this == REJECTED;
    }
}
