package duty.ledger.domain;

/** One attender's summed points for one period, as returned by the leaderboard query. */
public record PeriodTotal(Long attenderId, Long total) {}
