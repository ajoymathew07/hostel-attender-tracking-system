package in.ac.iitm.hostel.duty.categorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.ac.iitm.hostel.duty.categorization.domain.CategoryTerm;
import in.ac.iitm.hostel.duty.categorization.infrastructure.CategoryTermRepository;
import in.ac.iitm.hostel.duty.support.AbstractIntegrationTest;
import in.ac.iitm.hostel.duty.support.Fixtures;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * The classifier lexicon (data model section 3.7), which the manager edits without a redeploy.
 *
 * <p>The scoring itself lands in PR 10; what this PR owes is a table whose identity rule and
 * tombstoning work.
 */
@SpringBootTest
class CategoryTermIntegrationTest extends AbstractIntegrationTest {

    @Autowired private CategoryTermRepository terms;

    @Test
    @DisplayName("uq_term: one weight per (domain, term)")
    void termIsUniquePerDomain() {
        String term = Fixtures.unique("leak");
        terms.saveAndFlush(term("CIVIL", term));

        assertThatThrownBy(() -> terms.saveAndFlush(term("CIVIL", term)))
                .isInstanceOf(DataIntegrityViolationException.class);

        // The same word can carry weight in another domain.
        assertThat(terms.saveAndFlush(term("RO_PLANT", term)).getId()).isNotNull();
    }

    @Test
    @DisplayName("terms are normalised before storage, so uq_term matches what lookup asks for")
    void termsAreNormalised() {
        String raw = "  " + Fixtures.unique("Short Circuit").toUpperCase(java.util.Locale.ROOT) + " ";
        CategoryTerm stored =
                terms.saveAndFlush(
                        new CategoryTerm(
                                "ELECTRICAL",
                                raw,
                                new BigDecimal("2.50"),
                                Instant.parse("2026-01-01T00:00:00Z")));

        assertThat(stored.getTerm()).isEqualTo(CategoryTerm.normalizeTerm(raw));
        assertThat(terms.findByDomainAndTerm("ELECTRICAL", stored.getTerm())).isPresent();
    }

    @Test
    @DisplayName("retiring a term tombstones it, so a past classification stays explainable")
    void retiringIsATombstone() {
        CategoryTerm stored = terms.saveAndFlush(term("NETWORK", Fixtures.unique("wifi")));

        stored.retire();
        terms.saveAndFlush(stored);

        assertThat(terms.findById(stored.getId()).orElseThrow().isActive()).isFalse();
        assertThat(terms.findByDomainAndActiveIsTrue("NETWORK"))
                .extracting(CategoryTerm::getId)
                .doesNotContain(stored.getId());
        assertThat(terms.findByActiveIsTrue()).extracting(CategoryTerm::getId).doesNotContain(stored.getId());

        stored.reinstate();
        terms.saveAndFlush(stored);
        assertThat(terms.findByDomainAndActiveIsTrue("NETWORK"))
                .extracting(CategoryTerm::getId)
                .contains(stored.getId());
    }

    @Test
    @DisplayName("weight is NUMERIC(4,2) and round-trips exactly; fuzzy matching defaults on")
    void weightAndFlagsRoundTrip() {
        CategoryTerm stored =
                terms.saveAndFlush(
                        new CategoryTerm(
                                "ELECTRICAL",
                                Fixtures.unique("spark"),
                                new BigDecimal("3.25"),
                                Instant.parse("2026-01-01T00:00:00Z")));

        assertThat(stored.isFuzzy()).describedAs("transliterated text needs fuzzy by default — E14").isTrue();

        stored.reweight(new BigDecimal("0.50"));
        stored.setFuzzyMatching(false);
        terms.saveAndFlush(stored);

        CategoryTerm reloaded = terms.findById(stored.getId()).orElseThrow();
        assertThat(reloaded.getWeight()).isEqualByComparingTo(new BigDecimal("0.50"));
        assertThat(reloaded.isFuzzy()).isFalse();
        assertThat(reloaded.isActive()).isTrue();
        assertThat(reloaded.getDomain()).isEqualTo("ELECTRICAL");
        assertThat(reloaded.getCreatedAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
    }

    private CategoryTerm term(String domain, String term) {
        return new CategoryTerm(
                domain, term, new BigDecimal("1.00"), Instant.parse("2026-01-01T00:00:00Z"));
    }
}
