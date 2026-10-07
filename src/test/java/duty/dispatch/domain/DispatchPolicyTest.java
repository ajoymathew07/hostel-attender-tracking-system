package duty.dispatch.domain;

import static org.assertj.core.api.Assertions.assertThat;
import duty.dispatch.domain.DispatchPolicy.DispatchCandidate;
import duty.dispatch.domain.DispatchPolicy.DispatchDecision;
import duty.dispatch.domain.DispatchPolicy.FloorProximity;
import duty.dispatch.domain.DispatchPolicy.TieBreak;
import duty.dispatch.domain.FloorAssignment.Coverage;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The dispatch score, with no database. Asserts relationships (e.g. an overloaded primary loses to an
 * idle secondary) rather than exact numbers, so changing weights does not break it.
 */
class DispatchPolicyTest {

    private static final Instant LONG_AGO = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant RECENTLY = Instant.parse("2026-02-01T00:00:00Z");

    private final DispatchPolicy policy =
            new DispatchPolicy(0.60, 0.40, 8, new FloorProximity(1.00, 0.70, 0.40, 0.05, 0.10));

    private static DispatchCandidate candidate(
            long id, Coverage coverage, Integer distance, int load) {
        return new DispatchCandidate(id, coverage, distance, load, 0, LONG_AGO);
    }

    @Test
    @DisplayName("an idle primary wins: both terms point the same way")
    void theIdlePrimaryWins() {
        DispatchDecision decision =
                policy.decide(
                                List.of(
                                        candidate(1, Coverage.PRIMARY, 0, 0),
                                        candidate(2, Coverage.SECONDARY, 0, 0),
                                        candidate(3, null, 2, 0)))
                        .orElseThrow();

        assertThat(decision.attenderId()).isEqualTo(1);
        assertThat(decision.runnersUp())
                .describedAs("the snapshot has to show what the winner beat")
                .extracting(DispatchDecision.RunnerUp::attenderId)
                .containsExactly(2L, 3L);
    }

    @Test
    @DisplayName("an overloaded primary loses to an idle secondary — section 6.2's own example")
    void loadCanOverrideFloor() {
        DispatchDecision decision =
                policy.decide(
                                List.of(
                                        candidate(1, Coverage.PRIMARY, 0, 8),
                                        candidate(2, Coverage.SECONDARY, 0, 0)))
                        .orElseThrow();

        assertThat(decision.attenderId()).isEqualTo(2);
    }

    @Test
    @DisplayName("a slightly busy primary still beats an idle secondary: floor is the heavier term")
    void floorStillCarriesMoreWeight() {
        DispatchDecision decision =
                policy.decide(
                                List.of(
                                        candidate(1, Coverage.PRIMARY, 0, 2),
                                        candidate(2, Coverage.SECONDARY, 0, 0)))
                        .orElseThrow();

        assertThat(decision.attenderId()).isEqualTo(1);
    }

    @Test
    @DisplayName("distance decays but never to zero: the far attender is still a candidate")
    void distantAttendersStayDispatchable() {
        DispatchDecision decision =
                policy.decide(List.of(candidate(1, null, 5, 0), candidate(2, null, null, 0)))
                        .orElseThrow();

        assertThat(decision.floorComponent())
                .describedAs("a zero here would make the term uninformative when it matters most")
                .isGreaterThan(0);
        assertThat(decision.uncoveredFloor())
                .describedAs("nobody eligible covers this floor")
                .isTrue();
    }

    @Test
    @DisplayName("tied scores go to whoever was given less today — the anti-starvation rule")
    void theFirstTieBreakIsTodaysLoad() {
        DispatchDecision decision =
                policy.decide(
                                List.of(
                                        new DispatchCandidate(1, Coverage.PRIMARY, 0, 0, 4, LONG_AGO),
                                        new DispatchCandidate(2, Coverage.PRIMARY, 0, 0, 1, LONG_AGO)))
                        .orElseThrow();

        assertThat(decision.attenderId()).isEqualTo(2);
        assertThat(decision.tieBreak()).isEqualTo(TieBreak.FEWEST_TODAY);
    }

    @Test
    @DisplayName("then whoever has waited longest, and finally the lower id")
    void theRestOfTheChain() {
        assertThat(
                        policy.decide(
                                        List.of(
                                                new DispatchCandidate(1, Coverage.PRIMARY, 0, 0, 2, RECENTLY),
                                                new DispatchCandidate(2, Coverage.PRIMARY, 0, 0, 2, LONG_AGO)))
                                .orElseThrow())
                .satisfies(
                        decision -> {
                            assertThat(decision.attenderId()).isEqualTo(2);
                            assertThat(decision.tieBreak()).isEqualTo(TieBreak.LONGEST_IDLE);
                        });

        assertThat(
                        policy.decide(
                                        List.of(
                                                new DispatchCandidate(9, Coverage.PRIMARY, 0, 0, 2, RECENTLY),
                                                new DispatchCandidate(4, Coverage.PRIMARY, 0, 0, 2, RECENTLY)))
                                .orElseThrow())
                .satisfies(
                        decision -> {
                            assertThat(decision.attenderId()).isEqualTo(4);
                            assertThat(decision.tieBreak()).isEqualTo(TieBreak.LOWEST_ID);
                        });
    }

    @Test
    @DisplayName("identical inputs give an identical answer, whatever order they arrive in — R2")
    void theChoiceIsDeterministic() {
        List<DispatchCandidate> candidates =
                List.of(
                        new DispatchCandidate(7, Coverage.PRIMARY, 0, 0, 2, RECENTLY),
                        new DispatchCandidate(3, Coverage.PRIMARY, 0, 0, 2, RECENTLY));

        assertThat(policy.decide(candidates).orElseThrow().attenderId())
                .isEqualTo(policy.decide(candidates.reversed()).orElseThrow().attenderId());
    }

    @Test
    @DisplayName("everybody full still produces an assignment, and says the queue is saturated")
    void capacityOverflowAssignsAnyway() {
        DispatchDecision decision =
                policy.decide(
                                List.of(
                                        candidate(1, Coverage.PRIMARY, 0, 9),
                                        candidate(2, Coverage.SECONDARY, 0, 12)))
                        .orElseThrow();

        assertThat(decision.attenderId())
                .describedAs("work cannot vanish because everyone is busy")
                .isEqualTo(1);
        assertThat(decision.loadComponent()).isZero();
        assertThat(decision.capacityExceeded()).isTrue();
    }

    @Test
    @DisplayName("no eligible attender is the one way this declines — E = empty set, so T3")
    void anEmptyEligibleSetDeclines() {
        assertThat(policy.decide(List.of())).isEmpty();
    }

    @Test
    @DisplayName("the score stays inside [0, 1], which is what makes the weights read as shares")
    void scoresAreNormalised() {
        assertThat(policy.score(candidate(1, Coverage.PRIMARY, 0, 0))).isEqualTo(1.0);
        assertThat(policy.score(candidate(2, null, null, 100)))
                .isBetween(0.0, 1.0)
                .isEqualTo(0.60 * 0.10);
    }
}
