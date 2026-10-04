package in.ac.iitm.hostel.duty.workflow.application;

import in.ac.iitm.hostel.duty.settings.application.Settings;
import in.ac.iitm.hostel.duty.settings.application.SettingsStore;
import in.ac.iitm.hostel.duty.workflow.domain.ComplaintDomain;
import in.ac.iitm.hostel.duty.workflow.domain.Priority;
import in.ac.iitm.hostel.duty.workflow.domain.SlaTargets;
import in.ac.iitm.hostel.duty.workflow.domain.WorkingCalendar;
import java.time.Duration;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * The SLA table and the working day, as the manager currently has them (sections 4.5, 8.2).
 *
 * <p>Both sections say outright that these are configuration rather than constants and that the
 * manager sets them, and both are still open questions — OQ-2 for the targets, OQ-6 for the
 * window. The YAML holds the working answers; a row in {@code app_setting} overrides one cell.
 *
 * <p>Only the start and end of the working day are editable. The zone, the non-working days and
 * the holiday list stay in YAML: a hostel does not close on Sunday (which is why the list is
 * empty rather than absent), and a date-list editor is a screen the design never asks for.
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
     * The table and the calendar in force.
     *
     * <p>Package-private, and deliberately: these return {@code workflow.domain} types, which no
     * other module may see (architecture section 2.4, rule 3). {@link #configuration()} is the
     * published shape.
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

    /** The screen's key for one window: {@code ELECTRICAL.P1}. */
    static String cell(ComplaintDomain domain, Priority priority) {
        return domain.name() + "." + priority.name();
    }

    private static double toHours(Duration window) {
        return window.toMinutes() / 60d;
    }

    /** {@code workflow.sla.targets.ELECTRICAL.P1} — the same key the YAML uses. */
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
     * Validates a screenful of SLA edits and returns them as settings rows, so the screen can
     * save every section in one transaction.
     *
     * <p>A window of zero or less is refused: a due date at or before the moment of report is
     * breached the instant the complaint arrives, which would make every row on the dashboard red
     * and the breach metric meaningless.
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

    /** A cell the table does not have is a typo in a form field, not a new SLA window. */
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
