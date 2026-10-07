package duty.dispatch.domain;

import duty.dispatch.domain.FloorAssignment.Coverage;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Pattern;

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

    /**
     * One eligible attender, with everything the score and tie-breaks need.
     *
     * @param coverage their coverage of the complaint's floor, or {@code null} if none
     * @param floorDistance floors to the nearest floor they cover, or {@code null} if they cover none
     * @param weightedLoad 3 per P1, 2 per P2, 1 per P3 over their active tasks
     * @param lastAssignedAt {@code null} if they have never been assigned
     */
    public record DispatchCandidate(
            long attenderId,
            Coverage coverage,
            Integer floorDistance,
            int weightedLoad,
            long assignedToday,
            Instant lastAssignedAt) {}

    /**
     * Who dispatch chose and why; stored as {@code task_assignment.score_snapshot}.
     *
     * <p>Runners-up are kept because a score means little without the others to compare against.
     *
     * @param tieBreak {@code null} when the winner's score was highest outright
     * @param capacityExceeded everyone was full; the work was assigned anyway
     * @param uncoveredFloor nobody eligible covers the floor, so the choice was made on load alone
     */
    public record DispatchDecision(
            long attenderId,
            double score,
            double floorComponent,
            double loadComponent,
            List<RunnerUp> runnersUp,
            TieBreak tieBreak,
            boolean capacityExceeded,
            boolean uncoveredFloor) {

        /** An attender who scored and lost. */
        public record RunnerUp(long attenderId, double score) {}
    }

    /**
     * The floor term of the score: how close an attender works to the complaint's floor.
     *
     * <p>Distant attenders still score above zero, so the term still ranks people when the primary is
     * away.
     *
     * @param step subtracted for each floor of distance beyond adjacent
     * @param minimum the lowest value any distance gets
     */
    public record FloorProximity(
            double primary, double secondary, double adjacent, double step, double minimum) {

        /**
         * How close this attender is to the work.
         *
         * @param coverage their coverage of the complaint's floor, or {@code null} if none
         * @param distance floors to the nearest floor they cover, or {@code null} if they cover none
         */
        public double score(Coverage coverage, Integer distance) {
            if (coverage == Coverage.PRIMARY) {
                return primary;
            }
            if (coverage == Coverage.SECONDARY) {
                return secondary;
            }
            if (distance == null) {
                return minimum;
            }
            return Math.max(minimum, adjacent - step * (distance - 1));
        }
    }

    /**
     * The floor number a room number names: its first digit.
     *
     * <p>Cauvery rooms are digits only, three or four long (169 and 1099 are both floor 1). The design doc
     * says "first two digits of a 4-digit room", which is wrong. Anything else returns empty, and the
     * complaint goes to triage.
     */
    public static final class RoomFloor {

        private static final Pattern ROOM = Pattern.compile("\\d{3,4}");

        private RoomFloor() {}

        /** The floor a room number names, or empty if it is not a recognised room number. */
        public static OptionalInt floorNumber(String room) {
            if (room == null) {
                return OptionalInt.empty();
            }
            String trimmed = room.trim();
            if (!ROOM.matcher(trimmed).matches()) {
                return OptionalInt.empty();
            }
            return OptionalInt.of(trimmed.charAt(0) - '0');
        }
    }

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
}
