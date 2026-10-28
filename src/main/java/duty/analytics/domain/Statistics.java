package duty.analytics.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The arithmetic of section 8: summaries, the bottleneck rule, and the two statistical detectors.
 *
 * <p>Every rule has a minimum sample size below which it says nothing, because with five attenders and
 * twenty complaints a day a rule without one cries wolf (section 8.3).
 */
public final class Statistics {

    /** Below this many complaints in a domain, no bottleneck is named. */
    public static final int BOTTLENECK_MIN_N = 10;

    /** A stage must hold at least this share of the time to be called the bottleneck. */
    public static final double BOTTLENECK_SHARE = 0.35;

    /** A1 says nothing with fewer resolved complaints than this in the domain. */
    public static final int A1_MIN_N = 12;

    /** A1 flags a modified z-score above this (Iglewicz and Hoaglin). */
    public static final double A1_THRESHOLD = 3.5;

    /** A3 needs this many days of history before it judges a day. */
    public static final int A3_MIN_DAYS = 14;

    /** A3 flags a day whose count is less likely than this under the baseline. */
    public static final double A3_P_VALUE = 0.01;

    private static final double EWMA_ALPHA = 0.3;

    private Statistics() {}

    /** Mean, median and p90 of a sample, with its size, so a chart of six points says so. */
    public record Summary(int n, double mean, double median, double p90) {}

    /** The stage with the largest share of median time. */
    public record Bottleneck(String stage, double share) {}

    /** One A1 finding: which value, and its score. */
    public record Outlier(int index, double score) {}

    public static Summary summarise(List<Double> values) {
        if (values.isEmpty()) {
            return new Summary(0, 0, 0, 0);
        }
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        return new Summary(values.size(), mean, median(values), percentile(values, 0.90));
    }

    public static double median(List<Double> values) {
        List<Double> sorted = values.stream().sorted().toList();
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(middle) : (sorted.get(middle - 1) + sorted.get(middle)) / 2;
    }

    /** Nearest-rank percentile, so the answer is always a value that actually occurred. */
    public static double percentile(List<Double> values, double p) {
        List<Double> sorted = values.stream().sorted().toList();
        int rank = (int) Math.ceil(p * sorted.size());
        return sorted.get(Math.max(rank, 1) - 1);
    }

    /**
     * Section 8.2: the stage whose median is the largest share of the sum of stage medians, named only
     * when that share is at least 0.35 and the domain has at least ten complaints.
     */
    public static Optional<Bottleneck> bottleneck(Map<String, List<Double>> hoursByStage, int n) {
        if (n < BOTTLENECK_MIN_N || hoursByStage.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Double> medians =
                hoursByStage.entrySet().stream()
                        .collect(Collectors.toMap(Map.Entry::getKey, stage -> median(stage.getValue())));
        double total = medians.values().stream().mapToDouble(Double::doubleValue).sum();
        if (total <= 0) {
            return Optional.empty();
        }
        Map.Entry<String, Double> largest =
                medians.entrySet().stream().max(Comparator.comparingDouble(Map.Entry::getValue)).orElseThrow();
        double share = largest.getValue() / total;
        return share >= BOTTLENECK_SHARE ? Optional.of(new Bottleneck(largest.getKey(), share)) : Optional.empty();
    }

    /**
     * A1, prolonged resolution: values whose modified z-score {@code 0.6745 (x - median) / MAD} is above
     * 3.5. When the MAD is zero the score is undefined, and a value over three times the median is flagged
     * instead, scored as that ratio.
     */
    public static List<Outlier> prolonged(List<Double> values) {
        if (values.size() < A1_MIN_N) {
            return List.of();
        }
        double median = median(values);
        double mad = median(values.stream().map(x -> Math.abs(x - median)).toList());
        List<Outlier> flagged = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            double x = values.get(i);
            if (mad > 0) {
                double score = 0.6745 * (x - median) / mad;
                if (score > A1_THRESHOLD) {
                    flagged.add(new Outlier(i, score));
                }
            } else if (median > 0 && x > 3 * median) {
                flagged.add(new Outlier(i, x / median));
            }
        }
        return flagged;
    }

    /**
     * A3, arrival surge: the chance of seeing at least {@code count} complaints in a day, under a Poisson
     * whose rate is the EWMA ({@code alpha = 0.3}) of the days before. Empty with under 14 days of
     * history.
     */
    public static Optional<Double> surgeProbability(List<Integer> history, int count) {
        if (history.size() < A3_MIN_DAYS) {
            return Optional.empty();
        }
        double rate = history.get(0);
        for (int day = 1; day < history.size(); day++) {
            rate = EWMA_ALPHA * history.get(day) + (1 - EWMA_ALPHA) * rate;
        }
        return Optional.of(atLeast(count, rate));
    }

    /** P(X >= k) for X ~ Poisson(rate), as one minus the sum of the terms below k. */
    static double atLeast(int k, double rate) {
        double term = Math.exp(-rate);
        double below = 0;
        for (int i = 0; i < k; i++) {
            below += term;
            term *= rate / (i + 1);
        }
        return Math.max(0, 1 - below);
    }
}
