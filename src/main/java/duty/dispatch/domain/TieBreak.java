package duty.dispatch.domain;

/**
 * Which tie-break rule picked the winner, in the order they apply. Recorded so the snapshot explains a
 * tied choice.
 */
public enum TieBreak {

    /** Fewest tasks assigned today, so nobody is starved of work. */
    FEWEST_TODAY,

    /** Longest since their last assignment. */
    LONGEST_IDLE,

    /** Lowest user id, so there is always a winner. */
    LOWEST_ID
}
