package duty.workflow.domain;

import duty.workflow.domain.Complaint.ComplaintDomain;
import duty.workflow.domain.Complaint.Priority;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The working hours during which the SLA clock runs.
 *
 * <p>Why working hours: a complaint sent at 23:00 should not use up its 4-hour window overnight when
 * nobody is on duty. The default is 08:00–20:00 IST, Monday to Friday, set in YAML.
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
     * When {@code workingTime} of working time, starting at {@code from}, runs out. Time outside working
     * hours does not count.
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

    /** {@code at} if within working hours, otherwise the next opening time. */
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

    /**
     * The SLA window per domain and priority, in working hours. The values come from YAML.
     *
     * <p>{@link ComplaintDomain#UNCLASSIFIED} has no window, hence {@link Optional}: it gets a due date
     * once a manager sets its domain.
     */
    public record SlaTargets(Map<ComplaintDomain, Map<Priority, Duration>> windows) {

        public SlaTargets {
            windows = Map.copyOf(Objects.requireNonNull(windows, "windows"));
        }

        /** The working-hours window for this pair, or empty if the domain has no target. */
        public Optional<Duration> window(ComplaintDomain domain, Priority priority) {
            return Optional.ofNullable(windows.get(domain)).map(byPriority -> byPriority.get(priority));
        }
    }
}
