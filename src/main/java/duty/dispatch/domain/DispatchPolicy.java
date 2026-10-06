package duty.dispatch.domain;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The dispatch score: {@code Score = w_f · Floor + w_l · Load}, then the tie-breaks.
 *
 * <p>Both terms are in [0, 1] and the weights sum to 1, so the score is in [0, 1]. Pure Java with no
 * database, so it can be unit-tested directly.
 */
public record DispatchPolicy(
        double floorWeight, double loadWeight, int loadCap, FloorProximity proximity) {

    /**
     * Scores are compared to three decimals, so tiny floating-point differences count as a tie and the
     * visible tie-break rules decide.
     */
    private static final double PRECISION = 1000d;

    /**
     * The best eligible attender, or empty if there were none (the complaint then goes to triage).
     */
    public Optional<DispatchDecision> decide(List<DispatchCandidate> eligible) {
        if (eligible.isEmpty()) {
            return Optional.empty();
        }

        List<DispatchCandidate> ranked = eligible.stream().sorted(byScoreThenTieBreaks()).toList();
        DispatchCandidate best = ranked.get(0);

        return Optional.of(
                new DispatchDecision(
                        best.attenderId(),
                        rounded(score(best)),
                        rounded(proximity.score(best.coverage(), best.floorDistance())),
                        rounded(load(best)),
                        ranked.stream()
                                .skip(1)
                                .map(
                                        other ->
                                                new DispatchDecision.RunnerUp(
                                                        other.attenderId(), rounded(score(other))))
                                .toList(),
                        tieBreakAgainst(best, ranked),
                        eligible.stream().allMatch(candidate -> candidate.weightedLoad() >= loadCap),
                        eligible.stream().allMatch(candidate -> candidate.coverage() == null)));
    }

    /** The attender's score for this complaint. */
    public double score(DispatchCandidate candidate) {
        return floorWeight * proximity.score(candidate.coverage(), candidate.floorDistance())
                + loadWeight * load(candidate);
    }

    /** {@code 1 − min(1, load / loadCap)}: higher means more free capacity. */
    private double load(DispatchCandidate candidate) {
        return 1 - Math.min(1, (double) candidate.weightedLoad() / loadCap);
    }

    /**
     * Highest score, then fewest assigned today, then longest since last assignment, then lowest id. Never
     * assigned sorts first.
     */
    private Comparator<DispatchCandidate> byScoreThenTieBreaks() {
        return Comparator.comparingLong((DispatchCandidate candidate) -> -roundedUnits(score(candidate)))
                .thenComparingLong(DispatchCandidate::assignedToday)
                .thenComparing(
                        DispatchCandidate::lastAssignedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparingLong(DispatchCandidate::attenderId);
    }

    /** Which rule actually separated the winner from whoever matched their score, if any did. */
    private TieBreak tieBreakAgainst(DispatchCandidate best, List<DispatchCandidate> ranked) {
        Optional<DispatchCandidate> matched =
                ranked.stream()
                        .skip(1)
                        .filter(other -> roundedUnits(score(other)) == roundedUnits(score(best)))
                        .findFirst();
        if (matched.isEmpty()) {
            return null;
        }
        DispatchCandidate runnerUp = matched.get();
        if (best.assignedToday() != runnerUp.assignedToday()) {
            return TieBreak.FEWEST_TODAY;
        }
        if (!Objects.equals(best.lastAssignedAt(), runnerUp.lastAssignedAt())) {
            return TieBreak.LONGEST_IDLE;
        }
        return TieBreak.LOWEST_ID;
    }

    private static long roundedUnits(double score) {
        return Math.round(score * PRECISION);
    }

    private static double rounded(double score) {
        return roundedUnits(score) / PRECISION;
    }
}
