package in.ac.iitm.hostel.duty.workflow.domain;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * When attenders are expected to be working, and therefore when the SLA clock runs (section 4.5).
 *
 * <p><strong>The SLA figures of section 4.5 are working hours, not wall-clock hours.</strong> A
 * complaint submitted at 23:00 must not burn its four-hour window overnight while nobody is on
 * duty, because a breach that nobody could have prevented tells the manager nothing. So the window
 * is spent only inside the working day, and {@link #dueAt} walks forward day by day until it is.
 *
 * <p>The calendar is configuration, not a constant (section 4.5: "configurable by the manager
 * rather than compiled in"). The working default is OQ-6's: 08:00–20:00 IST, seven days a week,
 * with no holidays. {@code holidays} and {@code nonWorkingDays} are here because the design names
 * weekends and holidays as part of what a manager may set, even though the default set is empty.
 */
public record WorkingCalendar(
        ZoneId zone,
        LocalTime dayStart,
        LocalTime dayEnd,
        Set<DayOfWeek> nonWorkingDays,
        Set<LocalDate> holidays) {

    public WorkingCalendar {
        Objects.requireNonNull(zone, "zone");
        Objects.requireNonNull(dayStart, "dayStart");
        Objects.requireNonNull(dayEnd, "dayEnd");
        if (!dayStart.isBefore(dayEnd)) {
            throw new IllegalArgumentException("the working day must start before it ends");
        }
        nonWorkingDays =
                nonWorkingDays == null || nonWorkingDays.isEmpty()
                        ? EnumSet.noneOf(DayOfWeek.class)
                        : EnumSet.copyOf(nonWorkingDays);
        // Without this, dueAt would walk the calendar forever looking for a day that never comes.
        if (nonWorkingDays.size() == DayOfWeek.values().length) {
            throw new IllegalArgumentException("at least one day of the week must be a working day");
        }
        holidays = holidays == null ? Set.of() : Set.copyOf(holidays);
    }

    /**
     * When {@code workingTime} of working time, started at {@code from}, runs out.
     *
     * <p>A start outside the working day does not consume any of it: the clock begins at the next
     * working day's opening. That is the whole point of measuring in working hours.
     */
    public Instant dueAt(Instant from, Duration workingTime) {
        Objects.requireNonNull(from, "from");
        if (workingTime.isNegative()) {
            throw new IllegalArgumentException("an SLA window cannot be negative");
        }
        ZonedDateTime cursor = nextWorkingMoment(from.atZone(zone));
        Duration remaining = workingTime;
        while (true) {
            ZonedDateTime closing = cursor.with(dayEnd);
            Duration availableToday = Duration.between(cursor, closing);
            if (availableToday.compareTo(remaining) >= 0) {
                return cursor.plus(remaining).toInstant();
            }
            remaining = remaining.minus(availableToday);
            cursor = nextWorkingMoment(openingOn(cursor.toLocalDate().plusDays(1)));
        }
    }

    /** {@code at} if the hostel is open then, otherwise the next opening time. */
    private ZonedDateTime nextWorkingMoment(ZonedDateTime at) {
        ZonedDateTime cursor = at;
        while (true) {
            if (!isWorkingDay(cursor.toLocalDate())) {
                cursor = openingOn(cursor.toLocalDate().plusDays(1));
            } else if (cursor.toLocalTime().isBefore(dayStart)) {
                cursor = cursor.with(dayStart);
            } else if (!cursor.toLocalTime().isBefore(dayEnd)) {
                cursor = openingOn(cursor.toLocalDate().plusDays(1));
            } else {
                return cursor;
            }
        }
    }

    private boolean isWorkingDay(LocalDate date) {
        return !nonWorkingDays.contains(date.getDayOfWeek()) && !holidays.contains(date);
    }

    private ZonedDateTime openingOn(LocalDate date) {
        return date.atTime(dayStart).atZone(zone);
    }
}
