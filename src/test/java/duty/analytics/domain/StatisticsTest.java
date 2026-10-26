package duty.analytics.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import duty.analytics.domain.Statistics.Outlier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Summaries, the bottleneck rule and detectors A1 and A3, including their sample-size gates (section 8). */
class StatisticsTest {

    @Test
    @DisplayName("mean, median and nearest-rank p90, with n")
    void summarises() {
        var summary = Statistics.summarise(List.of(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 100.0));

        assertThat(summary.n()).isEqualTo(10);
        assertThat(summary.median()).isEqualTo(5.5);
        assertThat(summary.mean()).isEqualTo(14.5);
        assertThat(summary.p90()).isEqualTo(9.0);
        assertThat(Statistics.summarise(List.of()).n()).isZero();
        assertThat(Statistics.median(List.of(3.0, 1.0, 2.0))).isEqualTo(2.0);
    }

    @Test
    @DisplayName("a stage with at least 35% of the median time is the bottleneck; none below n = 10")
    void bottleneck() {
        Map<String, List<Double>> stages =
                Map.of("Pickup", List.of(1.0, 1.0), "Filing", List.of(6.0, 6.0), "Repair", List.of(3.0, 3.0));

        assertThat(Statistics.bottleneck(stages, 10))
                .hasValueSatisfying(
                        found -> {
                            assertThat(found.stage()).isEqualTo("Filing");
                            assertThat(found.share()).isEqualTo(0.6);
                        });
        assertThat(Statistics.bottleneck(stages, 9)).describedAs("below n = 10").isEmpty();
    }

    @Test
    @DisplayName("time spread evenly has no bottleneck")
    void evenSpreadHasNone() {
        Map<String, List<Double>> even =
                Map.of("A", List.of(1.0), "B", List.of(1.0), "C", List.of(1.0), "D", List.of(1.0));

        assertThat(Statistics.bottleneck(even, 20)).isEmpty();
        assertThat(Statistics.bottleneck(Map.of("A", List.of(0.0)), 20)).isEmpty();
    }

    @Test
    @DisplayName("A1 flags a modified z-score over 3.5, and says nothing below n = 12")
    void a1FlagsProlongedResolution() {
        List<Double> hours = new ArrayList<>(List.of(8.0, 9.0, 10.0, 10.0, 11.0, 12.0, 9.0, 10.0, 11.0, 10.0, 9.0));
        hours.add(80.0);

        List<Outlier> flagged = Statistics.prolonged(hours);
        assertThat(flagged).extracting(Outlier::index).containsExactly(11);
        assertThat(flagged.get(0).score()).isGreaterThan(Statistics.A1_THRESHOLD);

        assertThat(Statistics.prolonged(hours.subList(0, 11))).describedAs("n = 11").isEmpty();
    }

    @Test
    @DisplayName("A1 falls back to three times the median when the MAD is zero")
    void a1FallsBackWhenMadIsZero() {
        List<Double> hours = new ArrayList<>(Collections.nCopies(12, 5.0));
        hours.add(16.0);
        hours.add(14.0);

        assertThat(Statistics.prolonged(hours))
                .singleElement()
                .satisfies(
                        outlier -> {
                            assertThat(outlier.index()).isEqualTo(12);
                            assertThat(outlier.score()).isEqualTo(3.2);
                        });
    }

    @Test
    @DisplayName("A3 judges a day against an EWMA baseline, and only with 14 days of history")
    void a3ArrivalSurge() {
        List<Integer> quiet = Collections.nCopies(14, 2);

        assertThat(Statistics.surgeProbability(quiet, 12)).hasValueSatisfying(p -> assertThat(p).isLessThan(0.01));
        assertThat(Statistics.surgeProbability(quiet, 3)).hasValueSatisfying(p -> assertThat(p).isGreaterThan(0.01));
        assertThat(Statistics.surgeProbability(quiet.subList(0, 13), 12)).describedAs("13 days").isEmpty();
    }

    @Test
    @DisplayName("the Poisson tail: P(X >= 0) is 1, P(X >= 1) is 1 - e^-rate")
    void poissonTail() {
        assertThat(Statistics.atLeast(0, 2.0)).isEqualTo(1.0);
        assertThat(Statistics.atLeast(1, 2.0)).isCloseTo(1 - Math.exp(-2.0), within(1e-12));
    }

    @Test
    @DisplayName("percentile never goes below the first value")
    void percentileOfOne() {
        assertThat(Statistics.percentile(List.of(4.0), 0.0)).isEqualTo(4.0);
    }
}
