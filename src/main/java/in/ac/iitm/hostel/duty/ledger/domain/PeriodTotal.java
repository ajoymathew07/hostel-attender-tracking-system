package in.ac.iitm.hostel.duty.ledger.domain;

/**
 * One attender's summed points for one period — the {@code GROUP BY} half of the leaderboard
 * (section 7.8).
 *
 * <p>A balance is a {@code SUM} over immutable facts, never a stored counter (section 7.3), so
 * this is the shape a query returns and never the shape of a column.
 *
 * @param attenderId who earned them
 * @param total the signed sum; {@code null} is impossible because a group only exists if it has
 *     rows
 */
public record PeriodTotal(Long attenderId, Long total) {}
