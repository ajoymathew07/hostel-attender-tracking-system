package duty.ledger.application;

/** One attender's total for the period; {@code 0} if they have no points, so they still appear. */
public record LeaderboardRow(long attenderId, String displayName, long total) {}
