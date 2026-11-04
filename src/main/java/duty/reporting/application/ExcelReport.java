package duty.reporting.application;

import duty.analytics.application.AnalyticsService.DomainSummary;
import duty.analytics.application.AnalyticsService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The manager's Excel export (goal G6): one workbook with a tab each for the summary, the leaderboard,
 * every point movement and every complaint in the period. Times are India time, as on every screen.
 *
 * <p>Read with plain SQL, like the analytics, for the same reason. No student name, roll number, phone
 * or email is exported: the file leaves the system, and the bonus tally does not need them (11.4).
 */
@Service
@Transactional(readOnly = true)
@PreAuthorize("hasRole('MANAGER')")
public class ExcelReport {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final JdbcTemplate jdbc;
    private final AnalyticsService analytics;

    ExcelReport(JdbcTemplate jdbc, AnalyticsService analytics) {
        this.jdbc = jdbc;
        this.analytics = analytics;
    }

    /** The three periods the export offers, each ending today (India time). */
    public enum Period {
        DAILY,
        WEEKLY,
        MONTHLY;

        public LocalDate firstDay(LocalDate today) {
            return switch (this) {
                case DAILY -> today;
                case WEEKLY -> today.minusDays(6);
                case MONTHLY -> today.withDayOfMonth(1);
            };
        }
    }

    /** The workbook for the period, as the bytes of an {@code .xlsx} file. */
    public byte[] build(Period period) {
        LocalDate today = LocalDate.now(IST);
        LocalDate first = period.firstDay(today);
        Instant from = first.atStartOfDay(IST).toInstant();
        Instant to = today.plusDays(1).atStartOfDay(IST).toInstant();

        LocalDateTime fromUtc = LocalDateTime.ofInstant(from, ZoneOffset.UTC);
        LocalDateTime toUtc = LocalDateTime.ofInstant(to, ZoneOffset.UTC);

        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            sheet(
                    workbook,
                    "Summary",
                    List.of(
                            "Domain", "Received", "Resolved", "Rejected", "Still open", "SLA breached",
                            "Median hours", "Mean hours", "p90 hours", "Slowest stage"),
                    summary(from, to, fromUtc, toUtc));
            sheet(
                    workbook,
                    "Leaderboard",
                    List.of("Attender", "Points"),
                    rows(
                            """
                            SELECT u.display_name, SUM(l.points) AS points
                            FROM point_ledger l JOIN app_user u ON u.id = l.attender_id
                            WHERE l.effective_date BETWEEN ? AND ?
                            GROUP BY u.display_name ORDER BY points DESC, u.display_name
                            """,
                            first,
                            today));
            sheet(
                    workbook,
                    "Points",
                    List.of("Date", "Attender", "Complaint", "Reason", "Points", "Note"),
                    rows(
                            """
                            SELECT l.effective_date, u.display_name, c.public_ref, l.reason_code, l.points, l.note
                            FROM point_ledger l JOIN app_user u ON u.id = l.attender_id
                            LEFT JOIN task t ON t.id = l.task_id LEFT JOIN complaint c ON c.id = t.complaint_id
                            WHERE l.effective_date BETWEEN ? AND ?
                            ORDER BY l.effective_date, l.id
                            """,
                            first,
                            today));
            sheet(
                    workbook,
                    "Complaints",
                    List.of(
                            "Reference", "Reported", "Domain", "Room", "Complaint", "Priority", "Status", "Attender",
                            "Due", "SLA breached", "Docket", "Resolved", "Closed", "Hours to resolve"),
                    rows(
                            """
                            SELECT c.public_ref, %1$s AS reported, c.domain, c.reporter_room, c.raw_text, c.priority, c.status,
                                   (SELECT u.display_name FROM task t
                                      JOIN task_assignment a ON a.task_id = t.id AND a.status <> 'RELEASED'
                                      JOIN app_user u ON u.id = a.attender_id
                                    WHERE t.complaint_id = c.id ORDER BY a.id DESC LIMIT 1) AS attender,
                                   %2$s AS due, CASE WHEN c.sla_breached THEN 'Yes' ELSE 'No' END AS breached, c.docket_no,
                                   %3$s AS resolved, %4$s AS closed,
                                   round(extract(epoch FROM c.resolved_at - c.reported_at) / 3600.0, 1) AS hours
                            FROM complaint c WHERE c.reported_at >= ? AND c.reported_at < ?
                            ORDER BY c.reported_at
                            """.formatted(ist("c.reported_at"), ist("c.sla_due_at"), ist("c.resolved_at"), ist("c.closed_at")),
                            fromUtc,
                            toUtc));
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException impossible) {
            // Writing to memory does not fail.
            throw new UncheckedIOException(impossible);
        }
    }

    /**
     * One row per domain: counts for complaints reported in the period, then the resolution statistics and
     * the slowest stage from the analytics, blank where too few were resolved to say.
     */
    private List<List<Object>> summary(Instant from, Instant to, LocalDateTime fromUtc, LocalDateTime toUtc) {
        Map<String, DomainSummary> stats =
                analytics.byDomain(from, to).stream().collect(Collectors.toMap(DomainSummary::domain, Function.identity()));
        List<List<Object>> summary = new ArrayList<>();
        for (List<Object> counts :
                rows(
                        """
                        SELECT domain, count(*) AS received,
                               count(*) FILTER (WHERE resolved_at IS NOT NULL) AS resolved,
                               count(*) FILTER (WHERE status = 'REJECTED') AS rejected,
                               count(*) FILTER (WHERE status NOT IN ('RESOLVED', 'CLOSED', 'REJECTED')) AS still_open,
                               count(*) FILTER (WHERE sla_breached) AS breached
                        FROM complaint WHERE reported_at >= ? AND reported_at < ?
                        GROUP BY domain ORDER BY domain
                        """,
                        fromUtc,
                        toUtc)) {
            List<Object> row = new ArrayList<>(counts);
            DomainSummary domain = stats.get((String) counts.get(0));
            if (domain == null) {
                row.addAll(List.of("", "", "", ""));
            } else {
                row.addAll(List.of(domain.median(), domain.mean(), domain.p90(), domain.bottleneckStage() == null ? "" : domain.bottleneckStage()));
            }
            summary.add(row);
        }
        return summary;
    }

    /** A stored UTC timestamp as India time, e.g. {@code 2026-11-04 19:30}; blank when there is none. */
    private static String ist(String column) {
        return "to_char(%s AT TIME ZONE 'UTC' AT TIME ZONE 'Asia/Kolkata', 'YYYY-MM-DD HH24:MI')".formatted(column);
    }

    /** The rows of a query, in column order. Every column needs its own name: rows are read as maps. */
    private List<List<Object>> rows(String sql, Object... arguments) {
        return jdbc.queryForList(sql, arguments).stream()
                .map(Map::values)
                .map(values -> values.stream().map(value -> value == null ? (Object) "" : value).toList())
                .toList();
    }

    /** One tab: a bold header row, then the rows; numbers stay numbers so the sheet can sum them. */
    private static void sheet(Workbook workbook, String name, List<String> headers, List<List<Object>> rows) {
        Sheet sheet = workbook.createSheet(name);
        Font bold = workbook.createFont();
        bold.setBold(true);
        var headerStyle = workbook.createCellStyle();
        headerStyle.setFont(bold);
        Row header = sheet.createRow(0);
        for (int column = 0; column < headers.size(); column++) {
            header.createCell(column).setCellValue(headers.get(column));
            header.getCell(column).setCellStyle(headerStyle);
            sheet.setColumnWidth(column, 20 * 256);
        }
        for (int index = 0; index < rows.size(); index++) {
            Row row = sheet.createRow(index + 1);
            List<Object> values = rows.get(index);
            for (int column = 0; column < values.size(); column++) {
                Object value = values.get(column);
                if (value instanceof Number number) {
                    row.createCell(column).setCellValue(number.doubleValue());
                } else {
                    row.createCell(column).setCellValue(value.toString());
                }
            }
        }
    }
}
