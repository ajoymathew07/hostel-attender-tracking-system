package in.ac.iitm.hostel.duty.workflow.domain;

import java.util.List;

/**
 * The safety and utility words that make a complaint {@link Priority#P1} on arrival (section 4.5).
 *
 * <p><strong>Exact matching, deliberately.</strong> There is no fuzzy match, no stemming and no
 * edit distance here, and that is not an omission. A false {@code P1} from a near-miss spelling
 * jumps a routine complaint over genuine safety work and distorts the whole queue — so a word
 * either is one of these or it is not. The classifier of section 5.5 is where tolerance belongs; it
 * is choosing a domain, and being roughly right about "electrical" costs nothing like this.
 *
 * <p>Matching is on whole words for the same reason: "sparkling water is not available" is not a
 * live electrical hazard, and substring matching would file it as one.
 */
public final class SafetyKeywords {

    /** Section 4.5's list, verbatim. A phrase matches consecutive words. */
    private static final List<List<String>> PHRASES =
            List.of(
                    List.of("shock"),
                    List.of("spark"),
                    List.of("no", "water"),
                    List.of("sewage"),
                    List.of("short", "circuit"));

    private SafetyKeywords() {}

    /** Whether the resident's own words name one of section 4.5's hazards. */
    public static boolean hit(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        List<String> words = List.of(text.toLowerCase().split("[^a-z0-9]+"));
        return PHRASES.stream().anyMatch(phrase -> containsInOrder(words, phrase));
    }

    /** The base priority section 4.5's table gives a complaint as it arrives. */
    public static Priority basePriorityFor(String text) {
        return hit(text) ? Priority.P1 : Priority.P2;
    }

    private static boolean containsInOrder(List<String> words, List<String> phrase) {
        for (int start = 0; start + phrase.size() <= words.size(); start++) {
            if (words.subList(start, start + phrase.size()).equals(phrase)) {
                return true;
            }
        }
        return false;
    }
}
