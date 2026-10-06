package duty.workflow.domain;

/**
 * The complaint lifecycle states. Which moves are legal is defined by {@link ComplaintTransitions}.
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

    /** Whether no further transitions are possible. */
    public boolean isTerminal() {
        return this == CLOSED || this == REJECTED;
    }
}
