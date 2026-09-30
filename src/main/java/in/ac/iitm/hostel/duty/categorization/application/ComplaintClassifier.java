package in.ac.iitm.hostel.duty.categorization.application;

/**
 * The categorization module's published entry point (architecture section 2.4, rule 3).
 *
 * <p>One method, because section 5.5 describes one decision: given the resident's words, either a
 * domain that cleared the confidence gate, or triage.
 *
 * <p>Apache OpenNLP is deliberately absent. ADR-004 makes the weighted lexicon the primary and
 * only required classifier and tags {@code DocumentCategorizer} Extended, consulted later only for
 * the items the rules already route to triage — so it would be a second implementation of this
 * interface behind the first, not a replacement for it.
 */
public interface ComplaintClassifier {

    /**
     * Scores the text against the lexicon and applies the confidence gate.
     *
     * @param rawText the resident's wording, verbatim and unmodified
     */
    Classification classify(String rawText);
}
