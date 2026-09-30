package in.ac.iitm.hostel.duty.categorization.goldset;

import in.ac.iitm.hostel.duty.categorization.application.Classification;
import in.ac.iitm.hostel.duty.categorization.application.ComplaintClassifier;
import java.util.List;
import java.util.stream.Stream;

/**
 * Section 12.3's measurement: accuracy and coverage over a labelled split, with the per-domain
 * confusion matrix.
 *
 * <pre>
 *   coverage  = auto-accepted / total
 *   accuracy  = correct / auto-accepted        ← criterion G2, ≥ 90%
 *   recall_d  = correctly answered / truly d
 * </pre>
 *
 * <p><strong>Accuracy is never reported on its own</strong>, because on its own it is trivially
 * gameable: a classifier that refuses everything divides zero by zero and can be made to look
 * perfect. The pair is the measurement. Targets: accuracy ≥ 90%, coverage ≥ 70%.
 *
 * <p><strong>An item the human labelled {@code AMBIGUOUS} is expected to reach triage.</strong> Its
 * expected answer is therefore {@code TRIAGE}, so auto-accepting one lands in the accuracy
 * denominator and counts against the classifier — section 12.3 counts it as an error, not as
 * coverage earned. Triaging one is not credited either: triaged items are outside the denominator.
 */
final class Evaluation {

    /** What the classifier "answered" when it declined to: a {@code null} domain means triage. */
    static final String TRIAGE = "TRIAGE";

    /** The complaint domain vocabulary of section 1.5, fixed by the design rather than by the data. */
    static final List<String> DOMAINS = List.of("ELECTRICAL", "CIVIL", "RO_PLANT", "NETWORK");

    private final String name;
    private final List<Outcome> outcomes;

    private Evaluation(String name, List<Outcome> outcomes) {
        this.name = name;
        this.outcomes = outcomes;
    }

    /** Runs the classifier over one split. Nothing here tunes anything; it only records answers. */
    static Evaluation score(String name, List<GoldSet.Item> items, ComplaintClassifier classifier) {
        return new Evaluation(
                name,
                items.stream()
                        .map(item -> new Outcome(item, answerOf(classifier.classify(item.text()))))
                        .toList());
    }

    private static String answerOf(Classification classification) {
        return classification.domain() == null ? TRIAGE : classification.domain();
    }

    /** The answer a correct classifier would give: the domain, or triage for an ambiguous item. */
    private static String expectedAnswer(String label) {
        return GoldSet.AMBIGUOUS.equals(label) ? TRIAGE : label;
    }

    private record Outcome(GoldSet.Item item, String answer) {

        boolean autoAccepted() {
            return !TRIAGE.equals(answer);
        }

        boolean correct() {
            return answer.equals(expectedAnswer(item.label()));
        }
    }

    int total() {
        return outcomes.size();
    }

    int autoAccepted() {
        return (int) outcomes.stream().filter(Outcome::autoAccepted).count();
    }

    int correct() {
        return (int) outcomes.stream().filter(Outcome::autoAccepted).filter(Outcome::correct).count();
    }

    double coverage() {
        return total() == 0 ? 0 : (double) autoAccepted() / total();
    }

    /** Zero rather than 1.0 when nothing was accepted — a classifier that refuses everything has
     * demonstrated no accuracy, and 0/0 is the exact hole the coverage target exists to close. */
    double accuracy() {
        return autoAccepted() == 0 ? 0 : (double) correct() / autoAccepted();
    }

    /** Every distinct human label in this split, so a mislabelled row cannot hide from the matrix. */
    List<String> labels() {
        return outcomes.stream().map(outcome -> outcome.item().label()).distinct().sorted().toList();
    }

    /**
     * The whole measurement as text, printed by the test so the build log carries the numbers.
     *
     * <p>The misses are listed verbatim. That is safe here and nowhere else: this corpus is entirely
     * synthetic (section 11.4), and a failing accuracy gate is only actionable if it says which
     * items failed and what they were mistaken for.
     */
    String report() {
        StringBuilder out = new StringBuilder();
        out.append("%n=== gold set: %s — %d items ===%n".formatted(name, total()));
        out.append(
                "accuracy %.3f (%d/%d auto-accepted)   target >= 0.900%n"
                        .formatted(accuracy(), correct(), autoAccepted()));
        out.append(
                "coverage %.3f (%d/%d items)   target >= 0.700%n"
                        .formatted(coverage(), autoAccepted(), total()));

        out.append("%nrows are the human label, columns the classifier's answer%n".formatted());
        out.append("%-11s".formatted(""));
        for (String column : columns()) {
            out.append("%11s".formatted(column));
        }
        out.append("%11s%n".formatted("recall"));
        for (String label : labels()) {
            List<Outcome> row = outcomes.stream().filter(o -> o.item().label().equals(label)).toList();
            out.append("%-11s".formatted(label));
            for (String column : columns()) {
                out.append("%11d".formatted(row.stream().filter(o -> o.answer().equals(column)).count()));
            }
            long hits = row.stream().filter(Outcome::correct).count();
            out.append("%11.3f%n".formatted((double) hits / row.size()));
        }

        out.append("%nmisses (auto-accepted and wrong):%n".formatted());
        outcomes.stream()
                .filter(Outcome::autoAccepted)
                .filter(outcome -> !outcome.correct())
                .forEach(
                        outcome ->
                                out.append(
                                        "  %s -> %s : %s%n"
                                                .formatted(
                                                        outcome.item().label(),
                                                        outcome.answer(),
                                                        outcome.item().text())));
        return out.toString();
    }

    private static List<String> columns() {
        return Stream.concat(DOMAINS.stream(), Stream.of(TRIAGE)).toList();
    }
}
