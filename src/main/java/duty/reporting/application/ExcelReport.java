package duty.reporting.application;

import duty.analytics.application.AnalyticsService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
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
 * every point movement and every complaint in the period.
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

        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            List<List<Object>> summary =
                    analytics.byDomain(from, to).stream()
                            .map(row -> List.<Object>of(row.domain(), row.n(), row.median(), row.mean(), row.p90()))
                            .toList();
            sheet(workbook, "Summary", List.of("Domain", "Resolved (n)", "Median hours", "Mean hours", "p90 hours"), summary);
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
                    List.of("Date", "Attender", "Reason", "Points", "Note"),
                    rows(
                            """
                            SELECT l.effective_date, u.display_name, l.reason_code, l.points, l.note
                            FROM point_ledger l JOIN app_user u ON u.id = l.attender_id
                            WHERE l.effective_date BETWEEN ? AND ?
                            ORDER BY l.effective_date, l.id
                            """,
                            first,
                            today));
            sheet(
                    workbook,
                    "Complaints",
                    List.of("Reference", "Reported (UTC)", "Domain", "Room", "Priority", "Status", "Resolved (UTC)", "Docket"),
                    rows(
                            """
                            SELECT public_ref, reported_at, domain, reporter_room, priority, status, resolved_at, docket_no
                            FROM complaint WHERE reported_at >= ? AND reported_at < ?
                            ORDER BY reported_at
                            """,
                            LocalDateTime.ofInstant(from, ZoneOffset.UTC),
                            LocalDateTime.ofInstant(to, ZoneOffset.UTC)));
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException impossible) {
            // Writing to memory does not fail.
            throw new UncheckedIOException(impossible);
        }
    }

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
