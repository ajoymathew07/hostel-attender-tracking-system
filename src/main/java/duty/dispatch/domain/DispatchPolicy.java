package duty.dispatch.domain;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * <strong>The dispatch heuristic of section 6.2, stages 2 and 3.</strong>
 *
 * <p>{@code Score(a, c) = w_f · Floor(a, c) + w_l · Load(a)} — two terms, each in [0, 1], weights
 * summing to 1, so the score is in [0, 1] and each weight reads as "how much does this factor
 * matter". Stage 1, the availability gate, is not here: availability is a gate and not a score
 * (section 6.2), and the application layer applies it before anything reaches this class.
 *
 * <p>Pure, with no framework and no database, which is what lets section 12 pin the behaviour with
 * tests that assert <em>relationships</em> — an overloaded primary loses to an idle secondary —
 * rather than exact float outputs that tuning would break.
 *
 * @param floorWeight {@code w_f}
 * @param loadWeight {@code w_l}
 * @param loadCap {@code L_cap} — the weighted load at which an attender counts as full
 */
public record DispatchPolicy(
        double floorWeight, double loadWeight, int loadCap, FloorProximity proximity) {

    /**
     * Scores are compared at three decimals — the precision the snapshot records.
     *
     * <p>Two attenders whose scores differ in the twelfth decimal are tied as far as any human
     * reading the snapshot is concerned, and letting binary floating point settle that silently
     * would make the tie-break chain, which exists precisely to be visible, unreachable.
     */
    private static final double PRECISION = 1000d;

    /**
     * The best of the eligible attenders, or empty if there were none.
     *
     * <p>Empty is {@code E = ∅}: the complaint goes to triage as {@code NO_AVAILABLE_ATTENDER}
     * (T3). It is the one way this method declines, and it terminates (R1).
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

    /** {@code Score(a, c)}, as section 6.2 writes it. */
    public double score(DispatchCandidate candidate) {
        return floorWeight * proximity.score(candidate.coverage(), candidate.floorDistance())
                + loadWeight * load(candidate);
    }

    /**
     * {@code Load(a) = 1 − min(1, L(a) / L_cap)} — higher means <em>more</em> free capacity, so the
     * term pushes work toward idle attenders.
     */
    private double load(DispatchCandidate candidate) {
        return 1 - Math.min(1, (double) candidate.weightedLoad() / loadCap);
    }

    /**
     * Highest score, then section 6.2 stage 3's chain: fewest assigned today, longest since the
     * last assignment, lowest id.
     *
     * <p>A never-assigned attender sorts ahead of everyone, which is the same anti-starvation
     * intent as the rule above it.
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
