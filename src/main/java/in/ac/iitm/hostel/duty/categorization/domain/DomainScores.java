package in.ac.iitm.hostel.duty.categorization.domain;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * {@code S(d)} for every domain a term fired in, and section 5.5's confidence gate over them.
 *
 * <pre>
 *   confidence = 0                if S₁ = 0 or S₁ &lt; S_min
 *                (S₁ − S₂) / S₁   otherwise
 *
 *   accept if confidence ≥ τ  AND  S₁ ≥ S_min
 * </pre>
 *
 * <p><strong>Two gates, not one.</strong> A single absolute threshold accepts a complaint that
 * scores strongly for <em>two</em> domains equally — precisely the case a human should decide. A
 * single margin threshold accepts a complaint where one weak term matched and nothing else did.
 * Requiring both is what routes the uncertain cases to triage instead of guessing at them, and a
 * wrongly auto-assigned complaint sends an attender to the wrong job.
 *
 * <p>{@code S_min} and {@code τ} are passed in rather than held here: section 5.5 makes both
 * configuration, tuned against the gold set, and the domain layer reads no configuration.
 */
public final class DomainScores {

    private final Map<String, Double> totals = new LinkedHashMap<>();

    /** Adds {@code w(t) · m(t)} for one matched term to its domain's running total. */
    public void add(String domain, double score) {
        totals.merge(Objects.requireNonNull(domain, "domain"), score, Double::sum);
    }

    /**
     * The domain with the highest score, or {@code null} when nothing matched at all.
     *
     * <p>Ties break on the name so that two runs over the same text give the same answer. Which
     * name wins a tie never matters to the outcome — a tie makes the confidence zero, so the
     * complaint goes to triage either way — but a stable answer is what makes the classifier
     * reproducible for the gold-set evaluation (section 12.3).
     */
    public String leader() {
        return totals.entrySet().stream()
                .max(
                        Map.Entry.<String, Double>comparingByValue()
                                .thenComparing(Map.Entry.comparingByKey(Comparator.reverseOrder())))
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    /** Section 5.5's confidence: the winner's margin over the runner-up, gated on evidence. */
    public double confidence(double minimumScore) {
        double top = scoreAt(0);
        if (top == 0 || top < minimumScore) {
            return 0;
        }
        return (top - scoreAt(1)) / top;
    }

    /** Both gates. Anything this refuses becomes UNCLASSIFIED and lands in triage. */
    public boolean accepted(double minimumScore, double threshold) {
        return scoreAt(0) >= minimumScore && confidence(minimumScore) >= threshold;
    }

    /** {@code S₁} at rank 0, {@code S₂} at rank 1; zero when there is no such domain. */
    private double scoreAt(int rank) {
        return totals.values().stream()
                .sorted(Comparator.reverseOrder())
                .skip(rank)
                .findFirst()
                .orElse(0.0);
    }

    /** Domain names and numbers only — no complaint text is ever held here. */
    @Override
    public String toString() {
        return "DomainScores" + totals;
    }
}
