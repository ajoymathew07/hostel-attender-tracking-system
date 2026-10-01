package in.ac.iitm.hostel.duty.categorization.goldset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import in.ac.iitm.hostel.duty.categorization.application.ComplaintClassifier;
import in.ac.iitm.hostel.duty.support.AbstractIntegrationTest;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Acceptance criterion G2, measured: the gold-set evaluation of test plan section 12.3.
 *
 * <p>This is the test that fails if the lexicon regresses. A term retired or reweighted in a later
 * migration, or a threshold moved in {@code application.yml}, shows up here as a number below the
 * criterion rather than as a surprise at the demo.
 *
 * <p>It runs against real PostgreSQL and the seeded lexicon, because the seed <em>is</em> half the
 * classifier — a measurement over a hand-built term list would score something that does not ship.
 *
 * <p>Both splits are asserted. The holdout is the honest number and the one G2 is claimed on;
 * development is asserted too because that is the set tuning is allowed to touch, so a regression
 * should show there first.
 */
@SpringBootTest
class GoldSetEvaluationTest extends AbstractIntegrationTest {

    /** Section 12.3: accuracy ≥ 90%, coverage ≥ 70%, reported together and never in isolation. */
    private static final double REQUIRED_ACCURACY = 0.90;

    private static final double REQUIRED_COVERAGE = 0.70;

    @Autowired private ComplaintClassifier classifier;

    @Test
    @DisplayName("the holdout meets the ≥90% accuracy criterion at ≥70% coverage — G2")
    void theHoldoutMeetsTheCriterion() {
        assertMeetsCriterion(Evaluation.score("holdout", GoldSet.holdout(), classifier));
    }

    @Test
    @DisplayName("development meets it too, so a lexicon regression is caught on the tuning set")
    void developmentMeetsTheCriterion() {
        assertMeetsCriterion(Evaluation.score("development", GoldSet.development(), classifier));
    }

    @Test
    @DisplayName("the 70/30 split partitions the corpus and leaves no label out of either side")
    void theSplitIsSeventyThirtyOverTheWholeCorpus() {
        List<GoldSet.Item> development = GoldSet.development();
        List<GoldSet.Item> holdout = GoldSet.holdout();
        List<String> everyLabel =
                Stream.concat(Evaluation.DOMAINS.stream(), Stream.of(GoldSet.AMBIGUOUS)).toList();

        assertThat(development).doesNotContainAnyElementsOf(holdout);
        assertThat(development.size() + holdout.size()).isEqualTo(GoldSet.load().size());
        assertThat((double) development.size() / GoldSet.load().size()).isCloseTo(0.70, offset(0.05));

        // Scoring either split has to exercise all five labels, or the number says less than it
        // appears to; and a label the confusion matrix has no row for would vanish from it while
        // still counting against accuracy.
        assertThat(development).extracting(GoldSet.Item::label).containsOnlyElementsOf(everyLabel);
        assertThat(holdout).extracting(GoldSet.Item::label).containsOnlyElementsOf(everyLabel);
    }

    private void assertMeetsCriterion(Evaluation evaluation) {
        // Printed unconditionally: section 12.3's point is that the two numbers and the matrix are
        // the deliverable, not the green tick.
        System.out.println(evaluation.report());

        assertThat(evaluation.accuracy()).isGreaterThanOrEqualTo(REQUIRED_ACCURACY);
        assertThat(evaluation.coverage()).isGreaterThanOrEqualTo(REQUIRED_COVERAGE);
    }
}
