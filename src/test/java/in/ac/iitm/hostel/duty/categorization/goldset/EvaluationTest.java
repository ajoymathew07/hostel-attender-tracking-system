package in.ac.iitm.hostel.duty.categorization.goldset;

import static org.assertj.core.api.Assertions.assertThat;

import in.ac.iitm.hostel.duty.categorization.application.Classification;
import in.ac.iitm.hostel.duty.categorization.application.ComplaintClassifier;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The scoring rules of test plan section 12.3, over a stubbed classifier.
 *
 * <p>Deliberately not measured against the real lexicon: the thing under test here is the
 * <em>measurement</em>, and a measurement can only be checked by feeding it answers whose right
 * score is known in advance. {@link GoldSetEvaluationTest} is where the real classifier is scored.
 */
class EvaluationTest {

    private static final GoldSet.Item CIVIL = new GoldSet.Item("CIVIL", "water leaking from ceiling");
    private static final GoldSet.Item AMBIGUOUS =
            new GoldSet.Item(GoldSet.AMBIGUOUS, "water coming from ceiling fan");

    @Test
    @DisplayName("auto-accepting an item the human called ambiguous is an error, not coverage earned")
    void ambiguityAutoAcceptedIsAnError() {
        Evaluation evaluation =
                score(
                        List.of(CIVIL, AMBIGUOUS),
                        Map.of(CIVIL.text(), "CIVIL", AMBIGUOUS.text(), "CIVIL"));

        // Both were accepted, so coverage is full — and one of them is wrong, which is the point:
        // a classifier cannot buy coverage by guessing at the cases a human could not settle.
        assertThat(evaluation.coverage()).isEqualTo(1.0);
        assertThat(evaluation.autoAccepted()).isEqualTo(2);
        assertThat(evaluation.accuracy()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("an ambiguous item that reaches triage is outside the accuracy denominator")
    void ambiguityTriagedIsNotCounted() {
        Evaluation evaluation = score(List.of(CIVIL, AMBIGUOUS), Map.of(CIVIL.text(), "CIVIL"));

        assertThat(evaluation.accuracy()).isEqualTo(1.0);
        assertThat(evaluation.autoAccepted()).isEqualTo(1);
        // It still counts in coverage's denominator, so triaging it is not free either.
        assertThat(evaluation.coverage()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("a classifier that refuses everything scores zero accuracy, not a perfect one")
    void refusingEverythingIsNotPerfect() {
        // This is the hole section 12.3 reports coverage to close. 0/0 must not read as 100%.
        Evaluation evaluation = Evaluation.score("none", List.of(CIVIL), text -> unclassified());

        assertThat(evaluation.accuracy()).isZero();
        assertThat(evaluation.coverage()).isZero();
    }

    /** Stubs the classifier from a text-to-domain map; anything unmapped goes to triage. */
    private static Evaluation score(List<GoldSet.Item> items, Map<String, String> accepted) {
        ComplaintClassifier classifier =
                text ->
                        accepted.containsKey(text)
                                ? Classification.of(accepted.get(text), BigDecimal.ONE)
                                : unclassified();
        return Evaluation.score("stub", items, classifier);
    }

    private static Classification unclassified() {
        return Classification.unclassified(BigDecimal.ZERO);
    }
}
