package duty.workflow.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The working-hours clock. Times are written in UTC; 08:00–20:00 IST is 02:30–14:30 UTC. */
class WorkingCalendarTest {

    private static final WorkingCalendar SEVEN_DAYS_0800_TO_2000 =
            new WorkingCalendar(
                    ZoneId.of("Asia/Kolkata"),
                    LocalTime.of(8, 0),
                    LocalTime.of(20, 0),
                    Set.of(),
                    Set.of());

    @Test
    @DisplayName("a window that fits inside one working day is simple elapsed time")
    void insideOneWorkingDay() {
        // 10:00 IST + 4 working hours = 14:00 IST, the same as wall-clock, because the whole
        // window falls inside the day.
        Instant due =
                SEVEN_DAYS_0800_TO_2000.dueAt(
                        Instant.parse("2026-02-02T04:30:00Z"), Duration.ofHours(4));

        assertThat(due).isEqualTo(Instant.parse("2026-02-02T08:30:00Z"));
    }

    @Test
    @DisplayName("an overnight complaint does not burn its SLA while nobody is on duty")
    void anOvernightComplaintDoesNotBurnItsSla() {
        // Submitted 23:00 IST. Wall-clock arithmetic would make a P1 electrical complaint breach at
        // 03:00, before anyone could have looked at it. The clock starts at 08:00 instead.
        Instant reportedAt = Instant.parse("2026-02-01T17:30:00Z"); // 23:00 IST, 1 February
        Instant due = SEVEN_DAYS_0800_TO_2000.dueAt(reportedAt, Duration.ofHours(4));

        assertThat(due)
                .describedAs("08:00 IST the next morning plus four hours, i.e. 12:00 IST")
                .isEqualTo(Instant.parse("2026-02-02T06:30:00Z"));
    }

    @Test
    @DisplayName("a window longer than one day carries over to the following working days")
    void aWindowSpansSeveralDays() {
        // 12 working hours a day, so 24 working hours from 08:00 IST is the end of the second day.
        Instant due =
                SEVEN_DAYS_0800_TO_2000.dueAt(
                        Instant.parse("2026-02-02T02:30:00Z"), Duration.ofHours(24));

        assertThat(due).isEqualTo(Instant.parse("2026-02-03T14:30:00Z"));
    }

    @Test
    @DisplayName("a complaint submitted at closing time starts the next morning, not at 20:01")
    void closingTimeIsNotWorkingTime() {
        Instant due =
                SEVEN_DAYS_0800_TO_2000.dueAt(
                        Instant.parse("2026-02-02T14:30:00Z"), Duration.ofHours(1)); // 20:00 IST

        assertThat(due).isEqualTo(Instant.parse("2026-02-03T03:30:00Z")); // 09:00 IST next day
    }

    @Test
    @DisplayName("a zero-hour window is due at the next moment anyone is on duty")
    void aZeroWindowIsDueWhenWorkNextStarts() {
        Instant due =
                SEVEN_DAYS_0800_TO_2000.dueAt(Instant.parse("2026-02-01T20:00:00Z"), Duration.ZERO);

        assertThat(due).isEqualTo(Instant.parse("2026-02-02T02:30:00Z")); // 08:00 IST
    }

    @Test
    @DisplayName("configured non-working days and holidays are skipped over")
    void weekendsAndHolidaysAreSkipped() {
        WorkingCalendar sundaysOffAndOneHoliday =
                new WorkingCalendar(
                        ZoneId.of("Asia/Kolkata"),
                        LocalTime.of(8, 0),
                        LocalTime.of(20, 0),
                        EnumSet.of(DayOfWeek.SUNDAY),
                        Set.of(LocalDate.of(2026, 2, 9))); // the Monday after

        // Reported Saturday 19:00 IST with a 4-hour window: one hour left on Saturday, then Sunday
        // and Monday are closed, so it lands three hours into Tuesday.
        Instant due =
                sundaysOffAndOneHoliday.dueAt(
                        Instant.parse("2026-02-07T13:30:00Z"), Duration.ofHours(4));

        assertThat(due).isEqualTo(Instant.parse("2026-02-10T05:30:00Z")); // 11:00 IST Tuesday
    }

    @Test
    @DisplayName("a calendar that never opens is refused rather than searched forever")
    void anImpossibleCalendarIsRefused() {
        assertThatThrownBy(
                        () ->
                                new WorkingCalendar(
                                        ZoneId.of("Asia/Kolkata"),
                                        LocalTime.of(8, 0),
                                        LocalTime.of(20, 0),
                                        EnumSet.allOf(DayOfWeek.class),
                                        Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("working day");

        assertThatThrownBy(
                        () ->
                                new WorkingCalendar(
                                        ZoneId.of("Asia/Kolkata"),
                                        LocalTime.of(20, 0),
                                        LocalTime.of(8, 0),
                                        Set.of(),
                                        Set.of()))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(
                        () ->
                                SEVEN_DAYS_0800_TO_2000.dueAt(
                                        Instant.parse("2026-02-02T04:30:00Z"), Duration.ofHours(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
