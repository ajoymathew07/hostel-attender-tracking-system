package duty.workflow.application;

import duty.settings.application.Settings;
import duty.settings.application.SettingsStore;
import duty.workflow.domain.ComplaintDomain;
import duty.workflow.domain.Priority;
import duty.workflow.domain.SlaTargets;
import duty.workflow.domain.WorkingCalendar;
import java.time.Duration;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * The SLA windows and working day currently in force: YAML defaults, overridden by the settings
 * screen.
 *
 * <p>Only the start and end of the working day are editable; the zone, non-working days and holidays
 * stay in YAML.
 */
@Service
public class SlaSettings {

    private static final String TARGET_PREFIX = "workflow.sla.targets.";

    public static final String DAY_START = "workflow.sla.working-day.start";
    public static final String DAY_END = "workflow.sla.working-day.end";

    private final SlaTargets defaultTargets;
    private final WorkingCalendar defaultCalendar;
    private final SettingsStore store;

    SlaSettings(SlaTargets defaultTargets, WorkingCalendar defaultCalendar, SettingsStore store) {
        this.defaultTargets = defaultTargets;
        this.defaultCalendar = defaultCalendar;
        this.store = store;
    }

    /**
     * The windows in force. Package-private because it returns domain types other modules may not see.
     */
    SlaTargets targets() {
        return targetsFrom(store.current());
    }

    WorkingCalendar calendar() {
        return calendarFrom(store.current());
    }

    /** Everything the settings screen shows, in names and hours. */
    public SlaConfiguration configuration() {
        Settings current = store.current();
        WorkingCalendar calendar = calendarFrom(current);

        Map<String, Double> cells = new LinkedHashMap<>();
        targetsFrom(current)
                .windows()
                .forEach(
                        (domain, byPriority) ->
                                byPriority.forEach(
                                        (priority, window) ->
                                                cells.put(cell(domain, priority), toHours(window))));

        return new SlaConfiguration(
                Arrays.stream(ComplaintDomain.values())
                        .filter(ComplaintDomain::isDispatchable)
                        .map(ComplaintDomain::name)
                        .toList(),
                Arrays.stream(Priority.values()).map(Priority::name).toList(),
                cells,
                calendar.dayStart(),
                calendar.dayEnd(),
                calendar.zone().getId());
    }

    /** The screen's key for one window, e.g. {@code ELECTRICAL.P1}. */
    static String cell(ComplaintDomain domain, Priority priority) {
        return domain.name() + "." + priority.name();
    }

    private static double toHours(Duration window) {
        return window.toMinutes() / 60d;
    }

    /** The settings key, e.g. {@code workflow.sla.targets.ELECTRICAL.P1}, same as the YAML. */
    private static String targetKey(ComplaintDomain domain, Priority priority) {
        return TARGET_PREFIX + cell(domain, priority);
    }

    SlaTargets targetsFrom(Settings settings) {
        Map<ComplaintDomain, Map<Priority, Duration>> windows = new EnumMap<>(ComplaintDomain.class);
        defaultTargets
                .windows()
                .forEach(
                        (domain, byPriority) -> {
                            Map<Priority, Duration> overridden = new EnumMap<>(Priority.class);
                            byPriority.forEach(
                                    (priority, window) ->
                                            overridden.put(
                                                    priority,
                                                    settings.duration(targetKey(domain, priority), window)));
                            windows.put(domain, overridden);
                        });
        return new SlaTargets(windows);
    }

    WorkingCalendar calendarFrom(Settings settings) {
        return new WorkingCalendar(
                defaultCalendar.zone(),
                settings.time(DAY_START, defaultCalendar.dayStart()),
                settings.time(DAY_END, defaultCalendar.dayEnd()),
                defaultCalendar.nonWorkingDays(),
                defaultCalendar.holidays());
    }

    /**
     * Validates SLA edits and returns them as settings rows, so the screen can save all sections in one
     * transaction. Refuses windows of zero or less.
     */
    public Map<String, String> edits(
            Map<String, Double> cells, LocalTime dayStart, LocalTime dayEnd) {
        if (dayStart == null || dayEnd == null || !dayStart.isBefore(dayEnd)) {
            throw new IllegalArgumentException("the working day must start before it ends");
        }

        Map<String, String> edits = new LinkedHashMap<>();
        cells.forEach(
                (cell, hours) -> {
                    if (hours == null || hours <= 0) {
                        throw new IllegalArgumentException(
                                "an SLA window must be longer than zero, and " + cell + " is not");
                    }
                    edits.put(
                            TARGET_PREFIX + requireCell(cell),
                            Duration.ofMinutes(Math.round(hours * 60)).toString());
                });
        edits.put(DAY_START, dayStart.toString());
        edits.put(DAY_END, dayEnd.toString());
        return edits;
    }

    /** Rejects a key that is not in the table. */
    private static String requireCell(String cell) {
        String[] parts = cell.split("\\.");
        if (parts.length != 2) {
            throw new IllegalArgumentException("not an SLA window: " + cell);
        }
        try {
            return cell(ComplaintDomain.valueOf(parts[0]), Priority.valueOf(parts[1]));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("not an SLA window: " + cell, unknown);
        }
    }
}
