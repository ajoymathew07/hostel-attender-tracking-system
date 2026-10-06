package duty.workflow.domain;

import java.util.List;

/**
 * The safety words that make a complaint P1 on arrival.
 *
 * <p>Exact whole-word matching on purpose: a fuzzy match could wrongly jump a routine complaint ahead
 * of real safety work, and substring matching would read "sparkling" as "spark".
 */
public final class SafetyKeywords {

    /** The keyword list; a phrase matches consecutive words. */
    private static final List<List<String>> PHRASES =
            List.of(
                    List.of("shock"),
                    List.of("spark"),
                    List.of("no", "water"),
                    List.of("sewage"),
                    List.of("short", "circuit"));

    private SafetyKeywords() {}

    /** Whether the text contains a safety keyword. */
    public static boolean hit(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        List<String> words = List.of(text.toLowerCase().split("[^a-z0-9]+"));
        return PHRASES.stream().anyMatch(phrase -> containsInOrder(words, phrase));
    }

    /** P1 if a safety keyword appears, otherwise P2. */
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
