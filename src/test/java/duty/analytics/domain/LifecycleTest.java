package duty.analytics.domain;

import static org.assertj.core.api.Assertions.assertThat;

import duty.analytics.domain.Lifecycle.Transition;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Stage times and resolution time from audited transitions (sections 8.1, 8.2). */
class LifecycleTest {

    private static final Instant REPORTED = Instant.parse("2026-10-01T00:00:00Z");

    private static Instant hour(int h) {
        return REPORTED.plusSeconds(h * 3600L);
    }

    @Test
    @DisplayName("each stage gets the time between entering and leaving it; hold counts for none")
    void holdTimeIsExcluded() {
        List<Transition> moves =
                List.of(
                        new Transition("NEW", "ASSIGNED", hour(1)),
                        new Transition("ASSIGNED", "INSPECTING", hour(3)),
                        new Transition("INSPECTING", "AWAITING_CMS", hour(4)),
                        new Transition("AWAITING_CMS", "IN_PROGRESS", hour(10)),
                        new Transition("IN_PROGRESS", "ON_HOLD", hour(11)),
                        new Transition("ON_HOLD", "IN_PROGRESS", hour(61)),
                        new Transition("IN_PROGRESS", "RESOLVED", hour(62)),
                        new Transition("RESOLVED", "CLOSED", hour(70)));

        assertThat(Lifecycle.hoursByStage(REPORTED, moves))
                .containsEntry("Dispatch", 1.0)
                .containsEntry("Pickup", 2.0)
                .containsEntry("Inspection", 1.0)
                .containsEntry("Filing", 6.0)
                .containsEntry("Repair", 2.0)
                .containsEntry("Closure", 8.0)
                .doesNotContainKey("ON_HOLD");
        assertThat(Lifecycle.resolutionHours(REPORTED, moves)).contains(12.0);
    }

    @Test
    @DisplayName("a priority change on the complaint is not a status change and is skipped")
    void priorityChangesAreSkipped() {
        List<Transition> moves =
                List.of(
                        new Transition("NEW", "ASSIGNED", hour(1)),
                        new Transition("P3", "P2", hour(2)),
                        new Transition("ASSIGNED", "INSPECTING", hour(3)));

        assertThat(Lifecycle.hoursByStage(REPORTED, moves)).containsEntry("Pickup", 2.0);
    }

    @Test
    @DisplayName("a complaint never resolved has no resolution time")
    void unresolvedHasNone() {
        assertThat(Lifecycle.resolutionHours(REPORTED, List.of(new Transition("NEW", "TRIAGE", hour(1))))).isEmpty();
    }
}
