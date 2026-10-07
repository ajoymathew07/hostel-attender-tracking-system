package duty.workflow.infrastructure;

import duty.workflow.domain.Complaint.ComplaintDomain;
import duty.workflow.domain.Complaint.Priority;
import duty.workflow.domain.WorkingCalendar.SlaTargets;
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
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The default SLA windows and working day from {@code application.yml}. The manager can override them
 * on the settings screen.
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

    /** An unknown domain name in YAML fails startup. */
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

    /** The working day: zone, start, end, non-working days and holidays. */
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

    /**
     * Creates the SLA domain objects as beans from YAML. Domain classes cannot carry Spring annotations,
     * so the beans are declared here.
     */
    @Configuration
    @EnableConfigurationProperties(SlaProperties.class)
    static class WorkflowConfiguration {

        @Bean
        SlaTargets slaTargets(SlaProperties properties) {
            return properties.toSlaTargets();
        }

        @Bean
        WorkingCalendar workingCalendar(SlaProperties properties) {
            return properties.toWorkingCalendar();
        }
    }
}
