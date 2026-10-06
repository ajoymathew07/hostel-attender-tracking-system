package duty.dispatch.domain;

/**
 * Which rule of section 6.2 stage 3 settled a tie, in the order they are applied.
 *
 * <p>Recorded on the decision because "why them" has a different answer when two attenders scored
 * the same: the score alone no longer explains it, and the snapshot is what the manager reads
 * months later.
 */
public enum TieBreak {

    /** Fewest tasks assigned today — the anti-starvation term (R4). */
    FEWEST_TODAY,

    /** Longest since their last assignment. */
    LONGEST_IDLE,

    /** Lowest user id, which is what makes the whole chain total (R2). */
    LOWEST_ID
}
