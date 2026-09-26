package in.ac.iitm.hostel.duty.ledger.application;

import java.util.List;

/**
 * The standings for one period, highest first (section 7.8).
 *
 * <p>The period travels with the rows because {@code period_key} is derived from the Asia/Kolkata
 * business date (section 7.9) and the screen must say which month it is showing. Letting the view
 * work that out itself would put the business-zone rule in a second place.
 *
 * @param periodKey {@code 'YYYY-MM'}
 */
public record Leaderboard(String periodKey, List<LeaderboardRow> rows) {}
