package duty.ledger.application;

/**
 * One attender's standing (section 7.8).
 *
 * @param total the sum of every ledger row for this attender in the period, {@code 0} if they have
 *     none — an attender with no activity still appears, because a missing row would read as a bug
 *     to the manager
 */
public record LeaderboardRow(long attenderId, String displayName, long total) {}
