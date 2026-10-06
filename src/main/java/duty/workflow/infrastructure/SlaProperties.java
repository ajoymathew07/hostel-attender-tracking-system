package duty.workflow.infrastructure;

import duty.workflow.domain.ComplaintDomain;
import duty.workflow.domain.Priority;
import duty.workflow.domain.SlaTargets;
import duty.workflow.domain.WorkingCalendar;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The SLA table and the working calendar, as configuration (sections 4.5, 8.2).
 *
 * <p>Section 4.5 is explicit that these are the manager's to set rather than ours to compile in:
 * "the working calendar — working hours, weekends and holidays, and the initial SLA per domain — is
 * configurable by the manager rather than compiled in", and section 8.2 repeats it of the figures
 * ("These are configuration, not constants"). Both are also still open questions — OQ-2 for the
 * targets, OQ-6 for the window — so the defaults in YAML are working answers a config edit can
 * change, not a redeploy.
 */
@ConfigurationProperties(prefix = "workflow.sla")
public class SlaProperties {

    /** Working-hours window per domain and priority, keyed by enum name. */
    private Map<String, Map<String, Duration>> targets = new LinkedHashMap<>();

    private final WorkingDay workingDay = new WorkingDay();

    public SlaTargets toSlaTargets() {
        Map<ComplaintDomain, Map<Priority, Duration>> windows = new EnumMap<>(ComplaintDomain.class);
        targets.forEach(
                (domain, byPriority) -> {
                    Map<Priority, Duration> parsed = new EnumMap<>(Priority.class);
                    byPriority.forEach((priority, window) -> parsed.put(priority(priority), window));
                    windows.put(domain(domain), parsed);
                });
        return new SlaTargets(windows);
    }

    public WorkingCalendar toWorkingCalendar() {
        Set<DayOfWeek> closed = new HashSet<>();
        workingDay.nonWorkingDays.forEach(day -> closed.add(DayOfWeek.valueOf(day.toUpperCase())));
        Set<LocalDate> holidays = new HashSet<>();
        workingDay.holidays.forEach(date -> holidays.add(LocalDate.parse(date)));
        return new WorkingCalendar(
                ZoneId.of(workingDay.zone),
                LocalTime.parse(workingDay.start),
                LocalTime.parse(workingDay.end),
                closed,
                holidays);
    }

    /** A typo in a YAML key is a domain nobody has an SLA for, so it fails the start. */
    private static ComplaintDomain domain(String key) {
        try {
            return ComplaintDomain.valueOf(key.toUpperCase());
        } catch (IllegalArgumentException unknown) {
            throw new IllegalStateException("workflow.sla.targets names no such domain: " + key, unknown);
        }
    }

    private static Priority priority(String key) {
        try {
            return Priority.valueOf(key.toUpperCase());
        } catch (IllegalArgumentException unknown) {
            throw new IllegalStateException(
                    "workflow.sla.targets names no such priority: " + key, unknown);
        }
    }

    public Map<String, Map<String, Duration>> getTargets() {
        return targets;
    }

    public void setTargets(Map<String, Map<String, Duration>> targets) {
        this.targets = targets;
    }

    public WorkingDay getWorkingDay() {
        return workingDay;
    }

    /** OQ-6's window: 08:00–20:00 IST, seven days, no holidays. */
    public static class WorkingDay {

        private String zone = "Asia/Kolkata";
        private String start = "08:00";
        private String end = "20:00";
        private List<String> nonWorkingDays = new ArrayList<>();
        private List<String> holidays = new ArrayList<>();

        public String getZone() {
            return zone;
        }

        public void setZone(String zone) {
            this.zone = zone;
        }

        public String getStart() {
            return start;
        }

        public void setStart(String start) {
            this.start = start;
        }

        public String getEnd() {
            return end;
        }

        public void setEnd(String end) {
            this.end = end;
        }

        public List<String> getNonWorkingDays() {
            return nonWorkingDays;
        }

        public void setNonWorkingDays(List<String> nonWorkingDays) {
            this.nonWorkingDays = nonWorkingDays;
        }

        public List<String> getHolidays() {
            return holidays;
        }

        public void setHolidays(List<String> holidays) {
            this.holidays = holidays;
        }
    }
}
