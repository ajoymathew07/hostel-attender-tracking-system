package in.ac.iitm.hostel.duty.categorization.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Section 5.5's confidence gate, on its own.
 *
 * <p>The numbers here are the design's defaults: {@code S_min = 1.0}, {@code τ = 0.40}.
 */
class DomainScoresTest {

    private static final double S_MIN = 1.0;
    private static final double TAU = 0.40;

    @Test
    @DisplayName("one domain and nothing else is full confidence")
    void aClearWinner() {
        DomainScores scores = new DomainScores();
        scores.add("ELECTRICAL", 4.0);

        assertThat(scores.leader()).isEqualTo("ELECTRICAL");
        assertThat(scores.confidence(S_MIN)).isEqualTo(1.0);
        assertThat(scores.accepted(S_MIN, TAU)).isTrue();
    }

    @Test
    @DisplayName("scores for one domain accumulate across the terms that fired")
    void scoresAccumulate() {
        DomainScores scores = new DomainScores();
        scores.add("CIVIL", 3.0);
        scores.add("CIVIL", 1.5);
        scores.add("ELECTRICAL", 1.5);

        // (4.5 − 1.5) / 4.5
        assertThat(scores.confidence(S_MIN)).isCloseTo(0.667, within(0.001));
        assertThat(scores.accepted(S_MIN, TAU)).isTrue();
    }

    @Test
    @DisplayName("two domains neck and neck is zero confidence — the separation gate")
    void aTieIsNotAnAnswer() {
        DomainScores scores = new DomainScores();
        scores.add("CIVIL", 1.5);
        scores.add("ELECTRICAL", 1.5);

        assertThat(scores.confidence(S_MIN)).isZero();
        assertThat(scores.accepted(S_MIN, TAU)).isFalse();
    }

    @Test
    @DisplayName("one weak term and nothing else fails the absolute gate despite a clear margin")
    void weakEvidenceIsNotAnAnswerEither() {
        DomainScores scores = new DomainScores();
        scores.add("CIVIL", 0.7);

        // Uncontested, so the margin is perfect — and that is exactly the case the second gate is
        // for: a single near-miss on a weak word is not evidence.
        assertThat(scores.confidence(S_MIN)).isZero();
        assertThat(scores.accepted(S_MIN, TAU)).isFalse();
    }

    @Test
    @DisplayName("a real but narrow margin is refused: 0.25 is below τ")
    void aNarrowMarginIsRefused() {
        DomainScores scores = new DomainScores();
        scores.add("CIVIL", 4.0);
        scores.add("RO_PLANT", 3.0);

        assertThat(scores.confidence(S_MIN)).isEqualTo(0.25);
        assertThat(scores.accepted(S_MIN, TAU)).isFalse();
    }

    @Test
    @DisplayName("nothing matched at all: no leader, no confidence, no acceptance")
    void nothingMatched() {
        DomainScores scores = new DomainScores();

        assertThat(scores.leader()).isNull();
        assertThat(scores.confidence(S_MIN)).isZero();
        assertThat(scores.accepted(S_MIN, TAU)).isFalse();
        assertThat(scores).hasToString("DomainScores{}");
    }

    @Test
    @DisplayName("a tie picks the same leader on every run, so the classifier is reproducible")
    void tiesBreakDeterministically() {
        DomainScores oneOrder = new DomainScores();
        oneOrder.add("CIVIL", 1.5);
        oneOrder.add("ELECTRICAL", 1.5);

        DomainScores theOther = new DomainScores();
        theOther.add("ELECTRICAL", 1.5);
        theOther.add("CIVIL", 1.5);

        assertThat(oneOrder.leader()).isEqualTo(theOther.leader());
    }
}
