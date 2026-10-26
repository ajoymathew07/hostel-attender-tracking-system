package duty.analytics.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import duty.analytics.domain.AnalyticsAlert;
import duty.analytics.domain.Lifecycle.Transition;
import duty.analytics.domain.Lifecycle;
import duty.analytics.domain.Statistics.Bottleneck;
import duty.analytics.domain.Statistics.Outlier;
import duty.analytics.domain.Statistics.Summary;
import duty.analytics.domain.Statistics;
import duty.analytics.infrastructure.AnalyticsAlertRepository;
import duty.identity.application.CurrentUser;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolution time, the bottleneck stage and the anomaly detectors of section 8.
 *
 * <p>The figures are read with plain SQL over {@code complaint} and {@code audit_event}: these are
 * read-only aggregate queries across modules, and going through each module's repositories would only
 * load whole entities to throw most of each away. Timestamps are UTC in the database (NFR-7).
 *
 * <p>Detectors A1 to A4 are here. A5 (workload imbalance on three consecutive days) is not, because the
 * system keeps no history of daily load to compare against.
 */
@Service
@Transactional(readOnly = true)
public class AnalyticsService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final ObjectMapper JSON = new ObjectMapper();

    /** A1's reference set, and the dashboard's window. */
    public static final Duration WINDOW = Duration.ofDays(90);

    /** A2: this many complaints from one room in one domain is a chronic defect. */
    private static final int CHRONIC_COUNT = 3;

    private static final Map<String, Integer> SEVERITY_ORDER = Map.of("HIGH", 0, "WARN", 1, "INFO", 2);

    private final JdbcTemplate jdbc;
    private final AnalyticsAlertRepository alerts;
    private final String wing;

    AnalyticsService(JdbcTemplate jdbc, AnalyticsAlertRepository alerts, @Value("${dispatch.wing}") String wing) {
        this.jdbc = jdbc;
        this.alerts = alerts;
        this.wing = wing;
    }

    /**
     * One domain's resolution time in hours, with its sample size, and its bottleneck stage when there is
     * one ({@code bottleneckStage} is null otherwise).
     */
    public record DomainSummary(
            String domain, int n, double mean, double median, double p90, String bottleneckStage, double bottleneckShare) {}

    /** An open alert, as the dashboard lists it. */
    public record AlertView(
            long id, String detector, String severity, String subject, String summary, String evidence, Instant detectedAt) {}

    /** Resolution time and bottleneck per domain, over complaints reported in {@code [from, to)}. */
    @PreAuthorize("hasRole('MANAGER')")
    public List<DomainSummary> byDomain(Instant from, Instant to) {
        List<DomainSummary> rows = new ArrayList<>();
        byDomainLives(from, to)
                .forEach(
                        (domain, lives) -> {
                            List<Double> resolution = new ArrayList<>();
                            Map<String, List<Double>> stages = new LinkedHashMap<>();
                            for (Life life : lives) {
                                Lifecycle.resolutionHours(life.reportedAt(), life.moves())
                                        .ifPresent(
                                                hours -> {
                                                    resolution.add(hours);
                                                    Lifecycle.hoursByStage(life.reportedAt(), life.moves())
                                                            .forEach(
                                                                    (stage, spent) ->
                                                                            stages.computeIfAbsent(stage, any -> new ArrayList<>())
                                                                                    .add(spent));
                                                });
                            }
                            Summary summary = Statistics.summarise(resolution);
                            Optional<Bottleneck> bottleneck = Statistics.bottleneck(stages, resolution.size());
                            rows.add(
                                    new DomainSummary(
                                            domain,
                                            summary.n(),
                                            summary.mean(),
                                            summary.median(),
                                            summary.p90(),
                                            bottleneck.map(Bottleneck::stage).orElse(null),
                                            bottleneck.map(Bottleneck::share).orElse(0.0)));
                        });
        return rows;
    }

    /** Unacknowledged alerts, most severe first and newest first within a severity. */
    @PreAuthorize("hasRole('MANAGER')")
    public List<AlertView> openAlerts() {
        return alerts.findByAcknowledgedAtIsNullOrderByDetectedAtDesc().stream()
                .sorted(Comparator.comparing(alert -> SEVERITY_ORDER.getOrDefault(alert.getSeverity(), 3)))
                .map(
                        alert ->
                                new AlertView(
                                        alert.getId(),
                                        alert.getDetector(),
                                        alert.getSeverity(),
                                        alert.getSubjectType() + " " + alert.getSubjectRef(),
                                        alert.getSummary(),
                                        alert.getEvidence(),
                                        alert.getDetectedAt()))
                .toList();
    }

    @Transactional
    @PreAuthorize("hasRole('MANAGER')")
    public void acknowledge(long alertId) {
        alerts.findById(alertId).orElseThrow().acknowledge(CurrentUser.idOrNull(), Instant.now());
    }

    /** The "Run checks now" button. */
    @Transactional
    @PreAuthorize("hasRole('MANAGER')")
    public int runChecksNow() {
        return scan();
    }

    /** Runs A1 to A4 and stores what they find. Returns how many alerts were raised or refreshed. */
    @Transactional
    public int scan() {
        Instant now = Instant.now();
        LocalDate today = LocalDate.now(IST);
        return prolongedResolution(now) + chronicRooms(now, today) + arrivalSurge(now, today) + breachClusters(now, today);
    }

    /** A1: a complaint that took far longer to resolve than others in its domain over 90 days. */
    private int prolongedResolution(Instant now) {
        int raised = 0;
        for (Map.Entry<String, List<Life>> domain : byDomainLives(now.minus(WINDOW), now).entrySet()) {
            List<Life> resolved = new ArrayList<>();
            List<Double> hours = new ArrayList<>();
            for (Life life : domain.getValue()) {
                Lifecycle.resolutionHours(life.reportedAt(), life.moves())
                        .ifPresent(
                                spent -> {
                                    resolved.add(life);
                                    hours.add(spent);
                                });
            }
            for (Outlier outlier : Statistics.prolonged(hours)) {
                double median = Statistics.median(hours);
                double spent = hours.get(outlier.index());
                String ref = resolved.get(outlier.index()).ref();
                raise(
                        "A1",
                        "WARN",
                        "COMPLAINT",
                        ref,
                        "%s took %.0f h to resolve; the %s median is %.0f h".formatted(ref, spent, domain.getKey(), median),
                        Map.of(
                                "hours", spent,
                                "median", median,
                                "score", outlier.score(),
                                "threshold", Statistics.A1_THRESHOLD,
                                "n", hours.size(),
                                "window", "90d"),
                        "A1:COMPLAINT:" + ref,
                        now);
                raised++;
            }
        }
        return raised;
    }

    /** A2: three complaints in one domain from one room within 30 days; HIGH if within 14. */
    private int chronicRooms(Instant now, LocalDate today) {
        List<Map<String, Object>> rooms =
                jdbc.queryForList(
                        """
                        SELECT reporter_room AS room, domain, count(*) AS in30,
                               count(*) FILTER (WHERE reported_at >= ?) AS in14
                        FROM complaint
                        WHERE reporter_room IS NOT NULL AND domain <> 'UNCLASSIFIED' AND reported_at >= ?
                        GROUP BY reporter_room, domain
                        HAVING count(*) >= ?
                        """,
                        utc(now.minus(Duration.ofDays(14))),
                        utc(now.minus(Duration.ofDays(30))),
                        CHRONIC_COUNT);
        for (Map<String, Object> row : rooms) {
            String room = (String) row.get("room");
            String domain = (String) row.get("domain");
            int in30 = ((Number) row.get("in30")).intValue();
            int in14 = ((Number) row.get("in14")).intValue();
            raise(
                    "A2",
                    in14 >= CHRONIC_COUNT ? "HIGH" : "WARN",
                    "ROOM",
                    room,
                    "%d %s complaints from room %s in 30 days: the fixture may need replacing".formatted(in30, domain, room),
                    Map.of("in30Days", in30, "in14Days", in14, "threshold", CHRONIC_COUNT, "window", "30d"),
                    "A2:ROOM:%s:%s:%s".formatted(room, domain, YearMonth.from(today)),
                    now);
        }
        return rooms.size();
    }

    /** A3: yesterday's arrivals against an EWMA baseline of the days before it. */
    private int arrivalSurge(Instant now, LocalDate today) {
        LocalDate day = today.minusDays(1);
        Map<LocalDate, Integer> perDay = new HashMap<>();
        jdbc.query(
                """
                SELECT CAST(reported_at AT TIME ZONE 'UTC' AT TIME ZONE 'Asia/Kolkata' AS date) AS day, count(*) AS n
                FROM complaint WHERE reported_at >= ? AND reported_at < ?
                GROUP BY 1
                """,
                row -> {
                    perDay.put(row.getObject("day", LocalDate.class), row.getInt("n"));
                },
                utc(day.minusDays(60).atStartOfDay(IST).toInstant()),
                utc(today.atStartOfDay(IST).toInstant()));
        LocalDate first = perDay.keySet().stream().min(Comparator.naturalOrder()).orElse(day);
        List<Integer> history = first.datesUntil(day).map(date -> perDay.getOrDefault(date, 0)).toList();
        int count = perDay.getOrDefault(day, 0);
        Optional<Double> chance = Statistics.surgeProbability(history, count);
        if (chance.isEmpty() || chance.get() >= Statistics.A3_P_VALUE) {
            return 0;
        }
        raise(
                "A3",
                "WARN",
                "WING",
                wing,
                "%d complaints on %s, far more than usual".formatted(count, day),
                Map.of(
                        "count", count,
                        "pValue", chance.get(),
                        "threshold", Statistics.A3_P_VALUE,
                        "n", history.size(),
                        "window", history.size() + "d"),
                "A3:WING:%s:%s".formatted(wing, day),
                now);
        return 1;
    }

    /** A4: SLA breaches in the last 7 days above twice the weekly rate of the 8 weeks before. */
    private int breachClusters(Instant now, LocalDate today) {
        LocalDateTime earliest = jdbc.queryForObject("SELECT min(reported_at) FROM complaint", LocalDateTime.class);
        if (earliest == null || earliest.toInstant(ZoneOffset.UTC).isAfter(now.minus(Duration.ofDays(28)))) {
            return 0;
        }
        Instant weekAgo = now.minus(Duration.ofDays(7));
        List<Map<String, Object>> domains =
                jdbc.queryForList(
                        """
                        SELECT domain,
                               count(*) FILTER (WHERE sla_due_at >= ?) AS recent,
                               count(*) FILTER (WHERE sla_due_at < ?) AS earlier
                        FROM complaint
                        WHERE sla_breached AND sla_due_at >= ? AND sla_due_at < ?
                        GROUP BY domain
                        """,
                        utc(weekAgo),
                        utc(weekAgo),
                        utc(weekAgo.minus(Duration.ofDays(56))),
                        utc(now));
        int raised = 0;
        for (Map<String, Object> row : domains) {
            String domain = (String) row.get("domain");
            int recent = ((Number) row.get("recent")).intValue();
            double weeklyRate = ((Number) row.get("earlier")).intValue() / 8.0;
            if (recent > 2 * weeklyRate) {
                raise(
                        "A4",
                        "WARN",
                        "DOMAIN",
                        domain,
                        "%d %s SLA breaches in 7 days, against %.1f a week before".formatted(recent, domain, weeklyRate),
                        Map.of("breaches7Days", recent, "weeklyRate", weeklyRate, "threshold", "2x", "window", "8w"),
                        "A4:DOMAIN:%s:%s".formatted(domain, today.with(DayOfWeek.MONDAY)),
                        now);
                raised++;
            }
        }
        return raised;
    }

    /** Stores a finding, or refreshes the same finding seen before. */
    private void raise(
            String detector,
            String severity,
            String subjectType,
            String subjectRef,
            String summary,
            Map<String, Object> evidence,
            String dedupeKey,
            Instant now) {
        String json;
        try {
            json = JSON.writeValueAsString(evidence);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("alert evidence could not be serialised", impossible);
        }
        alerts.findByDedupeKey(dedupeKey)
                .ifPresentOrElse(
                        existing -> existing.refresh(severity, summary, json, now),
                        () ->
                                alerts.save(
                                        new AnalyticsAlert(
                                                detector, severity, subjectType, subjectRef, summary, json, now, dedupeKey)));
    }

    /** One complaint and its audited status changes, oldest first. */
    private record Life(long id, String ref, String domain, Instant reportedAt, List<Transition> moves) {}

    private Map<String, List<Life>> byDomainLives(Instant from, Instant to) {
        Map<Long, List<Transition>> moves = new HashMap<>();
        jdbc.query(
                """
                SELECT a.entity_id, a.from_state, a.to_state, a.occurred_at
                FROM audit_event a JOIN complaint c ON c.id = a.entity_id
                WHERE a.entity_type = 'complaint' AND a.to_state IS NOT NULL
                  AND c.reported_at >= ? AND c.reported_at < ?
                ORDER BY a.entity_id, a.occurred_at, a.id
                """,
                row -> {
                    moves.computeIfAbsent(row.getLong("entity_id"), any -> new ArrayList<>())
                            .add(
                                    new Transition(
                                            row.getString("from_state"),
                                            row.getString("to_state"),
                                            instant(row, "occurred_at")));
                },
                utc(from),
                utc(to));
        return jdbc
                .query(
                        "SELECT id, public_ref, domain, reported_at FROM complaint WHERE reported_at >= ? AND reported_at < ?",
                        (row, index) ->
                                new Life(
                                        row.getLong("id"),
                                        row.getString("public_ref"),
                                        row.getString("domain"),
                                        instant(row, "reported_at"),
                                        moves.getOrDefault(row.getLong("id"), List.of())),
                        utc(from),
                        utc(to))
                .stream()
                .collect(Collectors.groupingBy(Life::domain, TreeMap::new, Collectors.toList()));
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        return row.getObject(column, LocalDateTime.class).toInstant(ZoneOffset.UTC);
    }
}
