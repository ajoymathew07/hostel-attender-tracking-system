package in.ac.iitm.hostel.duty.categorization;

import static org.assertj.core.api.Assertions.assertThat;

import in.ac.iitm.hostel.duty.categorization.application.Classification;
import in.ac.iitm.hostel.duty.categorization.application.ComplaintClassifier;
import in.ac.iitm.hostel.duty.support.AbstractIntegrationTest;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The weighted lexicon against the seeded terms (sections 5.5, 12.3; ADR-004).
 *
 * <p>Against the real seed and real PostgreSQL rather than a stubbed term list, because the seed
 * <em>is</em> half the classifier: a weight chosen wrongly in the migration is a
 * misclassification, and a test over hand-built terms would prove the arithmetic while telling us
 * nothing about the thing that ships.
 *
 * <p>The complaints here are synthetic. No real resident's words are committed to this repository
 * (section 11.4).
 */
@SpringBootTest
class LexiconClassifierIntegrationTest extends AbstractIntegrationTest {

    @Autowired private ComplaintClassifier classifier;

    @Test
    @DisplayName("an obvious complaint in each domain is accepted with high confidence")
    void oneObviousCaseADomain() {
        assertThat(classifier.classify("Tube light in the corridor is flickering and the fan stopped"))
                .extracting(Classification::domain)
                .isEqualTo("ELECTRICAL");
        assertThat(classifier.classify("Water leaking from the bathroom ceiling"))
                .extracting(Classification::domain)
                .isEqualTo("CIVIL");
        assertThat(classifier.classify("No water in the RO plant since yesterday"))
                .extracting(Classification::domain)
                .isEqualTo("RO_PLANT");
        assertThat(classifier.classify("Internet is very slow in my room"))
                .extracting(Classification::domain)
                .isEqualTo("NETWORK");
    }

    @Test
    @DisplayName("an uncontested domain scores the full 1.000, which is what the gate compares")
    void confidenceIsReported() {
        Classification classification = classifier.classify("No water in the RO plant since yesterday");

        assertThat(classification.confidence()).isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    @DisplayName("a misspelling still classifies — E14, and the reason fuzzy matching exists")
    void aMisspellingStillLands() {
        assertThat(classifier.classify("wifii not workin, plz fix"))
                .extracting(Classification::domain)
                .isEqualTo("NETWORK");
    }

    @Test
    @DisplayName("a transliterated spelling clears the absolute gate on a single fuzzy hit")
    void aTransliterationStillLands() {
        // `geyzer` earns 0.70 of the term's 1.50, which is 1.05 — just over S_min. That is the
        // 1.50 weight band doing its job: an ordinary term spelled wrong is still evidence.
        assertThat(classifier.classify("geyzer not working, no hot water"))
                .extracting(Classification::domain)
                .isEqualTo("ELECTRICAL");
    }

    @Test
    @DisplayName("an ambiguous complaint goes to triage rather than getting a confident wrong domain")
    void ambiguityGoesToTriage() {
        // Section 10.3's own example. `ceiling` is Civil and `fan` is Electrical, both at 1.50,
        // so the scores are level and the separation gate refuses to pick one. Sending an attender
        // to the wrong job erodes trust in the whole system; triage costs the manager three seconds.
        Classification classification = classifier.classify("water coming from ceiling fan");

        assertThat(classification.domain()).isNull();
        assertThat(classification.confidence()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("text with nothing in the lexicon goes to triage, not to a guess")
    void nothingMatchedGoesToTriage() {
        assertThat(classifier.classify("please look into this when you can").domain()).isNull();
    }

    @Test
    @DisplayName("a phrase suppresses its own words, so `no water` is not counted twice")
    void aPhraseIsNotDoubleCounted() {
        // `no water` is 3.00 on its own. If `water` were also credited the total would change but
        // the domain would not, so the assertion that proves suppression is the margin: RO Plant
        // alone fires, and Civil's `water leaking` never sees a free `water` token to latch onto.
        Classification classification = classifier.classify("no water");

        assertThat(classification.domain()).isEqualTo("RO_PLANT");
        assertThat(classification.confidence()).isEqualByComparingTo(BigDecimal.ONE);
    }
}
