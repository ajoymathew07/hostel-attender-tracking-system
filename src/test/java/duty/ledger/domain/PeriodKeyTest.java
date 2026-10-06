package duty.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Month boundaries (section 7.9, NFR-7) — a plain unit test.
 *
 * <p>The case that matters is E21: a task resolved at 02:00 IST on 1 October belongs to October.
 * Grouping the UTC instant naively would have filed it under September, silently and wrongly.
 */
class PeriodKeyTest {

    @Test
    @DisplayName("a period key is the zero-padded YYYY-MM of the business date")
    void keyIsZeroPadded() {
        assertThat(PeriodKey.of(LocalDate.of(2026, 1, 31))).isEqualTo("2026-01");
        assertThat(PeriodKey.of(LocalDate.of(2026, 10, 1))).isEqualTo("2026-10");
        assertThat(PeriodKey.of(LocalDate.of(2026, 12, 31))).isEqualTo("2026-12");
        assertThat(PeriodKey.of(LocalDate.of(2026, 9, 1))).hasSize(PeriodKey.LENGTH);
    }

    @Test
    @DisplayName("02:00 IST on 1 October falls in October, not September — E21")
    void monthBoundaryUsesTheBusinessDate() {
        // 2026-10-01T02:00 IST is 2026-09-30T20:30 UTC.
        Instant justAfterMidnightInChennai = Instant.parse("2026-09-30T20:30:00Z");

        assertThat(PeriodKey.businessDateOf(justAfterMidnightInChennai))
                .isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(PeriodKey.ofInstant(justAfterMidnightInChennai))
                .describedAs("which is what the manager expects of the October leaderboard")
                .isEqualTo("2026-10");
    }

    @Test
    @DisplayName("an instant safely inside the previous month stays there")
    void ordinaryInstantsAreUnsurprising() {
        Instant lateSeptember = Instant.parse("2026-09-28T12:00:00Z");

        assertThat(PeriodKey.businessDateOf(lateSeptember)).isEqualTo(LocalDate.of(2026, 9, 28));
        assertThat(PeriodKey.ofInstant(lateSeptember)).isEqualTo("2026-09");
    }

    @Test
    @DisplayName("the business zone is Asia/Kolkata, and null input is rejected")
    void businessZoneAndGuards() {
        assertThat(PeriodKey.BUSINESS_ZONE.getId()).isEqualTo("Asia/Kolkata");
        assertThatThrownBy(() -> PeriodKey.of(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> PeriodKey.businessDateOf(null)).isInstanceOf(NullPointerException.class);
    }
}
