package duty;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Page timestamps are shown in India time, not as raw UTC. */
class IstTimeTest {

    private final IstTime ist = new IstTime();

    @Test
    @DisplayName("a UTC instant is shown 5:30 ahead, in a readable form")
    void showsIndiaTime() {
        assertThat(ist.format(Instant.parse("2026-10-13T04:52:53Z"))).isEqualTo("13 Oct 2026, 10:22 AM");
    }

    @Test
    @DisplayName("an evening UTC instant rolls over to the next India date")
    void rollsOverTheDate() {
        assertThat(ist.format(Instant.parse("2026-10-13T20:00:00Z"))).isEqualTo("14 Oct 2026, 1:30 AM");
    }

    @Test
    @DisplayName("no instant gives null, so a template can fall back to its own text")
    void nullStaysNull() {
        assertThat(ist.format(null)).isNull();
    }
}
