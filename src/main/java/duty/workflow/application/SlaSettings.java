package duty.workflow.application;

import duty.settings.application.SettingsStore.Settings;
import duty.settings.application.SettingsStore;
import duty.workflow.domain.Complaint.ComplaintDomain;
import duty.workflow.domain.Complaint.Priority;
import duty.workflow.domain.Complaint;
import duty.workflow.domain.WorkingCalendar.SlaTargets;
import duty.workflow.domain.WorkingCalendar;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    /**
     * The SLA table and working day as the settings screen shows them, in names and hours.
     *
     * @param cells e.g. {@code "ELECTRICAL.P1" → 4.0}
     */
    public record SlaConfiguration(
            List<String> domains,
            List<String> priorities,
            Map<String, Double> cells,
            LocalTime dayStart,
            LocalTime dayEnd,
            String zone) {}

    /**
     * Computes the SLA due date and the moment ageing escalates, so both are calculated the same way
     * everywhere.
     */
    @Service
    public static class SlaClock {

        private final SlaSettings settings;
        private final SettingsStore store;

        SlaClock(SlaSettings settings, SettingsStore store) {
            this.settings = settings;
            this.store = store;
        }

        /**
         * Sets {@code sla_due_at} from the domain and priority, counting working hours from when the resident
         * submitted (not when ingestion noticed). An {@code UNCLASSIFIED} complaint gets no due date until a
         * manager sets its domain.
         */
        public void schedule(Complaint complaint) {
            // One read, so the table and the calendar that produce a due date are the same edit.
            Settings current = store.current();
            SlaTargets targets = settings.targetsFrom(current);
            WorkingCalendar calendar = settings.calendarFrom(current);
            targets
                    .window(complaint.getDomain(), complaint.getPriority())
                    .ifPresent(
                            window ->
                                    complaint.scheduleSla(
                                            calendar.dueAt(complaint.getReportedAt(), window)));
        }

        /**
         * When the complaint passes 1.5× its SLA window and is due an escalation: half a window after
         * {@code sla_due_at}.
         *
         * <p>Measured from the due date because it already includes working hours and hold extensions. Uses
         * the base priority's window, so each escalation does not move the next threshold.
         */
        public Optional<Instant> ageingDueAt(Complaint complaint) {
            if (complaint.getSlaDueAt() == null) {
                return Optional.empty();
            }
            Settings current = store.current();
            WorkingCalendar calendar = settings.calendarFrom(current);
            return settings
                    .targetsFrom(current)
                    .window(complaint.getDomain(), complaint.getBasePriority())
                    .map(window -> calendar.dueAt(complaint.getSlaDueAt(), halfOf(window)));
        }

        private static Duration halfOf(Duration window) {
            return window.dividedBy(2);
        }
    }
}
