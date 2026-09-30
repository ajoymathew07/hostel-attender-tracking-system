package in.ac.iitm.hostel.duty.categorization.domain;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The resident's wording, put into the shape the lexicon can be matched against — the first four
 * steps of section 5.5's pipeline:
 *
 * <pre>
 *   lowercase, Unicode NFKC, strip punctuation, collapse whitespace
 *     → expand abbreviations
 *     → tokenize (whitespace + n-grams up to 3, so phrases can be matched)
 * </pre>
 *
 * <p>Phrases are what make the lexicon work. Section 5.5: {@code water} alone is nearly useless —
 * it appears in RO Plant, Civil and Electrical complaints — while {@code no water} and
 * {@code water leaking} are decisive. So {@link #phrases()} offers the three-token spans first and
 * the single tokens last, which is what lets the caller let a phrase suppress its own parts.
 *
 * <p>Immutable, and free of anything but the JDK, so the whole pipeline is unit-testable without a
 * database or a Spring context (architecture section 2.4, rule 1).
 */
public final class ComplaintText {

    /** Section 5.5: "n-grams up to 3". */
    public static final int LONGEST_PHRASE = 3;

    /**
     * Section 5.5's abbreviation list, expanded before tokenizing so that {@code no wtr} reaches
     * the lexicon as the phrase {@code no water}.
     */
    private static final Map<String, String> ABBREVIATIONS =
            Map.of("ac", "air conditioner", "wtr", "water", "elec", "electrical");

    /** Punctuation is a separator, not a character: "fan-not working!" is three tokens. */
    private static final Pattern SEPARATORS = Pattern.compile("[^\\p{IsAlphabetic}\\p{IsDigit}]+");

    private final List<String> tokens;

    private ComplaintText(List<String> tokens) {
        this.tokens = tokens;
    }

    public static ComplaintText of(String rawText) {
        String folded =
                Normalizer.normalize(Objects.requireNonNull(rawText, "rawText"), Normalizer.Form.NFKC)
                        .toLowerCase(Locale.ROOT);
        List<String> tokens = new ArrayList<>();
        for (String token : SEPARATORS.split(folded)) {
            if (!token.isEmpty()) {
                // An expansion is itself several tokens ("ac" → "air", "conditioner"), so it is
                // split here rather than left as one token no phrase could line up with.
                tokens.addAll(List.of(ABBREVIATIONS.getOrDefault(token, token).split(" ")));
            }
        }
        return new ComplaintText(List.copyOf(tokens));
    }

    public List<String> tokens() {
        return tokens;
    }

    /**
     * Every span of one to three tokens, <strong>longest first</strong>.
     *
     * <p>The order is the contract. A caller that takes spans in this order and refuses one whose
     * tokens are already spoken for gets section 5.5's "phrase matches suppress their constituent
     * single-token matches" for free, with no second pass.
     */
    public List<Phrase> phrases() {
        List<Phrase> phrases = new ArrayList<>();
        for (int size = LONGEST_PHRASE; size >= 1; size--) {
            for (int start = 0; start + size <= tokens.size(); start++) {
                phrases.add(
                        new Phrase(start, size, String.join(" ", tokens.subList(start, start + size))));
            }
        }
        return phrases;
    }

    /**
     * Carries the resident's words.
     *
     * <p>Complaint text is personal data (section 11.4) and this object is the natural thing to
     * attach to a diagnostic line while tuning the classifier, so its {@code toString} says how
     * much text there was and never what it said.
     */
    @Override
    public String toString() {
        return "ComplaintText[" + tokens.size() + " tokens]";
    }

    /**
     * One candidate span of the text.
     *
     * @param start index of its first token
     * @param size how many tokens it covers, 1 to {@link #LONGEST_PHRASE}
     * @param text the span joined by single spaces, ready to compare with a stored term
     */
    public record Phrase(int start, int size, String text) {

        /** Only single tokens are fuzzy-matched — section 5.5 bounds the edit distance on tokens. */
        public boolean isSingleToken() {
            return size == 1;
        }

        public boolean overlaps(boolean[] taken) {
            for (int index = start; index < start + size; index++) {
                if (taken[index]) {
                    return true;
                }
            }
            return false;
        }

        public void take(boolean[] taken) {
            for (int index = start; index < start + size; index++) {
                taken[index] = true;
            }
        }

        /** Never prints the span itself: it is the resident's wording (section 11.4). */
        @Override
        public String toString() {
            return "Phrase[start=" + start + ", size=" + size + "]";
        }
    }
}
