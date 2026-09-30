package in.ac.iitm.hostel.duty.categorization.application;

import in.ac.iitm.hostel.duty.categorization.domain.CategoryTerm;
import in.ac.iitm.hostel.duty.categorization.domain.ComplaintText;
import in.ac.iitm.hostel.duty.categorization.domain.DomainScores;
import in.ac.iitm.hostel.duty.categorization.infrastructure.CategoryTermRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.apache.commons.text.similarity.JaroWinklerSimilarity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Section 5.5's weighted lexicon with fuzzy matching, gated on confidence — the primary and only
 * required classifier (ADR-004).
 *
 * <pre>
 *   S(d) = Σ w(t) · m(t)   over the terms t of domain d that fired
 *
 *   m(t) = 1.00  exact token or phrase match
 *          0.70  fuzzy match (Jaro–Winkler ≥ 0.90)
 * </pre>
 *
 * <p><strong>Fuzzy matching applies to single tokens only</strong>, as section 5.5 writes it
 * ("on tokens of length ≥ 4"). It is what makes a misspelling or a Tamil/English transliteration
 * still land — complaint text is short, often under ten words, and frequently misspelled — and it
 * is also the one part of the scoring that can invent a match that is not there, so its reach is
 * bounded three ways: the token must be at least four characters, it must be within two characters
 * of the term's length (section 5.5 bounds the edit distance at 2, so the lengths cannot differ by
 * more), and the term itself must have {@code fuzzy} set, which the manager controls per row.
 *
 * <p><strong>A term that fires credits every domain that holds it.</strong> {@code leak} belongs
 * to Civil and to RO Plant, and that is not a bug to be resolved by picking one — it is how the
 * ambiguity the manager has to settle actually shows up in the scores.
 *
 * <p>The lexicon is read on every call rather than cached. It is one indexed query over a few
 * dozen rows at ingest time, and section 5.5's whole claim is that the manager can edit the
 * lexicon without a redeploy — which a cache would make untrue until something evicted it.
 */
@Service
@Transactional(readOnly = true)
class LexiconClassifier implements ComplaintClassifier {

    private static final JaroWinklerSimilarity SIMILARITY = new JaroWinklerSimilarity();

    /** Section 5.5's fuzzy threshold and the credit a fuzzy hit earns. */
    private static final double NEAR_MISS = 0.90;

    private static final double FUZZY_CREDIT = 0.70;
    private static final double EXACT_CREDIT = 1.00;

    /** "on tokens of length ≥ 4" — below that, near-misses are different words. */
    private static final int SHORTEST_FUZZY_TOKEN = 4;

    /** "Damerau-Levenshtein ≤ 2" bounds the length gap too: a near-miss is not a longer phrase. */
    private static final int WIDEST_LENGTH_GAP = 2;

    private final CategoryTermRepository lexicon;
    private final double minimumScore;
    private final double threshold;

    LexiconClassifier(
            CategoryTermRepository lexicon,
            @Value("${categorization.minimum-score}") double minimumScore,
            @Value("${categorization.confidence-threshold}") double threshold) {
        this.lexicon = lexicon;
        this.minimumScore = minimumScore;
        this.threshold = threshold;
    }

    @Override
    public Classification classify(String rawText) {
        List<CategoryTerm> terms = lexicon.findByActiveIsTrue();
        ComplaintText text = ComplaintText.of(rawText);
        DomainScores scores = new DomainScores();
        boolean[] taken = new boolean[text.tokens().size()];

        // Longest span first, and a span whose tokens are already spoken for is skipped: that is
        // section 5.5's "phrase matches suppress their constituent single-token matches", so
        // `no water` is counted once rather than as itself plus `water`.
        for (ComplaintText.Phrase phrase : text.phrases()) {
            if (phrase.overlaps(taken)) {
                continue;
            }
            List<CategoryTerm> fired = exactMatches(terms, phrase.text());
            double credit = EXACT_CREDIT;
            if (fired.isEmpty() && phrase.isSingleToken()) {
                fired = nearMisses(terms, phrase.text());
                credit = FUZZY_CREDIT;
            }
            if (fired.isEmpty()) {
                continue;
            }
            for (CategoryTerm term : fired) {
                scores.add(term.getDomain(), term.getWeight().doubleValue() * credit);
            }
            phrase.take(taken);
        }

        BigDecimal confidence =
                BigDecimal.valueOf(scores.confidence(minimumScore)).setScale(3, RoundingMode.HALF_UP);
        return scores.accepted(minimumScore, threshold)
                ? Classification.of(scores.leader(), confidence)
                : Classification.unclassified(confidence);
    }

    private static List<CategoryTerm> exactMatches(List<CategoryTerm> terms, String phrase) {
        return terms.stream().filter(term -> term.getTerm().equals(phrase)).toList();
    }

    private static List<CategoryTerm> nearMisses(List<CategoryTerm> terms, String token) {
        if (token.length() < SHORTEST_FUZZY_TOKEN) {
            return List.of();
        }
        return terms.stream().filter(term -> term.isFuzzy() && isNearMiss(token, term.getTerm())).toList();
    }

    private static boolean isNearMiss(String token, String term) {
        return Math.abs(token.length() - term.length()) <= WIDEST_LENGTH_GAP
                && SIMILARITY.apply(token, term) >= NEAR_MISS;
    }
}
