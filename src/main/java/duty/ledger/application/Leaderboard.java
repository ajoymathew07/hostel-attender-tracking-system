package duty.ledger.application;

import java.util.List;

/** The standings for one {@code YYYY-MM} period, highest first. */
public record Leaderboard(String periodKey, List<LeaderboardRow> rows) {}
