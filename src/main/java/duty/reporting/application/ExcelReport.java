package duty.reporting.application;

import duty.analytics.application.AnalyticsService;
import duty.analytics.application.AnalyticsService.DomainSummary;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The manager's Excel export (goal G6), laid out like the hostel's own register: a dashboard, then one
 * tab each for daily duties, electrical and civil complaints, the RO plant log, special duties,
 * attendance and discipline, the leaderboard, and every complaint. Dates are India time.
 *
 * <p>Read with plain SQL, like the analytics. No student name, roll number, phone or email is exported:
 * the file leaves the system, and none of these tabs needs them (11.4).
 */
@Service
@Transactional(readOnly = true)
@PreAuthorize("hasRole('MANAGER')")
public class ExcelReport {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    /** Done means fixed: resolved, or resolved and signed off. */
    private static final String DONE = "('RESOLVED', 'CLOSED')";

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

    /** The workbook for one of the preset periods, ending today. */
    public byte[] build(Period period) {
        LocalDate today = LocalDate.now(IST);
        return build(period.firstDay(today), today);
    }

    /**
     * The workbook for the days {@code first} to {@code last}, both included (India time), as the bytes
     * of an {@code .xlsx} file. Days Pending is still counted up to today, since that is how long the
     * complaint has really been waiting.
     */
    public byte[] build(LocalDate first, LocalDate last) {
        LocalDate today = LocalDate.now(IST);
        Instant from = first.atStartOfDay(IST).toInstant();
        Instant to = last.plusDays(1).atStartOfDay(IST).toInstant();
        // Timestamps are stored as UTC wall-clock time, so the bounds are passed the same way.
        LocalDateTime fromUtc = LocalDateTime.ofInstant(from, ZoneOffset.UTC);
        LocalDateTime toUtc = LocalDateTime.ofInstant(to, ZoneOffset.UTC);

        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Styles styles = new Styles(workbook);
            dashboard(workbook, styles, first, last, from, to, fromUtc, toUtc);

            // Everything that earned or took back points, apart from penalties, which have their own tab.
            table(workbook, styles, "Daily Duty Updates",
                    List.of("Date", "Staff Name", "Floor", "Duty Type", "Remarks", "Points"),
                    rows("""
                            SELECT l.effective_date AS day, u.display_name AS staff, %s AS floor,
                                   coalesce(c.reporter_room || ' ' || c.raw_text, c.raw_text, t.title, l.note, '') AS duty,
                                   CASE l.reason_code WHEN 'REVERSAL' THEN 'Reopened, points taken back'
                                                      WHEN 'MANUAL_ADJUSTMENT' THEN 'Adjustment'
                                                      ELSE 'Completed' END AS remarks,
                                   l.points AS points
                            FROM point_ledger l JOIN app_user u ON u.id = l.attender_id
                            LEFT JOIN task t ON t.id = l.task_id LEFT JOIN complaint c ON c.id = t.complaint_id
                            WHERE l.reason_code <> 'PENALTY' AND l.effective_date BETWEEN ? AND ?
                            ORDER BY l.effective_date, l.id
                            """.formatted(FLOOR), first, last));

            for (String domain : List.of("ELECTRICAL", "CIVIL")) {
                table(workbook, styles, label(domain) + " Complaints",
                        List.of("Date", "Complaint", "Assigned To", "Status", "Closed Date", "Days Pending", "IVRS"),
                        rows("""
                                SELECT %1$s AS day, coalesce(c.reporter_room || ' ', '') || c.raw_text AS complaint,
                                       coalesce(%2$s, '') AS assigned_to,
                                       CASE WHEN c.status IN %3$s THEN 'Completed'
                                            WHEN c.status = 'REJECTED' THEN 'Rejected' ELSE 'Pending' END AS status,
                                       %4$s AS closed,
                                       CASE WHEN c.status IN %3$s OR c.status = 'REJECTED' THEN 0
                                            ELSE CAST(? AS date) - %1$s END AS days_pending,
                                       coalesce(c.docket_no, '') AS ivrs
                                FROM complaint c
                                WHERE c.domain = ? AND c.reported_at >= ? AND c.reported_at < ?
                                ORDER BY c.reported_at
                                """.formatted(istDate("c.reported_at"), ATTENDER, DONE, istDate("c.resolved_at")),
                                today, domain, fromUtc, toUtc));
            }

            // The app has no separate RO register: a check is a special duty with "RO plant" and
            // "morning" or "evening" in its title, and it counts on the day its points were given.
            table(workbook, styles, "RO Plant Log",
                    List.of("Date", "Morning Check", "Evening Check", "Updated By"),
                    rows("""
                            SELECT l.effective_date AS day,
                                   coalesce(max(CASE WHEN t.title ILIKE '%morning%' THEN 'DONE' END), '') AS morning,
                                   coalesce(max(CASE WHEN t.title ILIKE '%evening%' THEN 'DONE' END), '') AS evening,
                                   u.display_name AS updated_by
                            FROM point_ledger l JOIN task t ON t.id = l.task_id JOIN app_user u ON u.id = l.attender_id
                            WHERE l.reason_code = 'SPECIAL_DUTY' AND t.title ILIKE '%RO plant%'
                              AND l.effective_date BETWEEN ? AND ?
                            GROUP BY l.effective_date, u.display_name
                            ORDER BY l.effective_date, u.display_name
                            """, first, last));

            // One row per person on a duty, so a shared duty lists everyone who took it.
            table(workbook, styles, "Manager Special Duty",
                    List.of("Date", "Duty Assigned", "Staff Name", "Completed", "Points", "Remarks"),
                    rows("""
                            SELECT %s AS day, t.title || coalesce(': ' || nullif(t.description, ''), '') AS duty,
                                   coalesce(u.display_name, '') AS staff,
                                   CASE WHEN t.status = 'CANCELLED' THEN 'Task Cancelled'
                                        WHEN a.status = 'COMPLETED' THEN 'yes' ELSE 'no' END AS completed,
                                   t.point_value AS points,
                                   CASE WHEN a.id IS NULL AND t.status = 'OPEN' THEN 'Nobody has taken it yet'
                                        WHEN t.status = 'EXPIRED' THEN 'Expired' ELSE '' END AS remarks
                            FROM task t
                            LEFT JOIN task_assignment a ON a.task_id = t.id AND a.status <> 'RELEASED'
                            LEFT JOIN app_user u ON u.id = a.attender_id
                            WHERE t.task_type = 'SPECIAL_DUTY' AND t.created_at >= ? AND t.created_at < ?
                            ORDER BY t.created_at, t.id, u.display_name
                            """.formatted(istDate("t.created_at")), fromUtc, toUtc));

            // Penalties, with the manager's note saying what was missed.
            table(workbook, styles, "Attendance & Discipline",
                    List.of("Date", "Staff", "Missed Update", "Deduction"),
                    rows("""
                            SELECT l.effective_date AS day, u.display_name AS staff,
                                   coalesce(l.note, '') AS missed, l.points AS deduction
                            FROM point_ledger l JOIN app_user u ON u.id = l.attender_id
                            WHERE l.reason_code = 'PENALTY' AND l.effective_date BETWEEN ? AND ?
                            ORDER BY l.effective_date, l.id
                            """, first, last));

            // Every active attender, on zero if they did nothing; ties share a rank.
            table(workbook, styles, "Leaderboard",
                    List.of("Staff", "Points Earned", "Deductions", "Final Score", "Final Rank"),
                    rows("""
                            SELECT u.display_name AS staff,
                                   coalesce(sum(l.points) FILTER (WHERE l.reason_code <> 'PENALTY'), 0) AS earned,
                                   coalesce(sum(l.points) FILTER (WHERE l.reason_code = 'PENALTY'), 0) AS deductions,
                                   coalesce(sum(l.points), 0) AS final_score,
                                   rank() OVER (ORDER BY coalesce(sum(l.points), 0) DESC) AS final_rank
                            FROM app_user u
                            LEFT JOIN point_ledger l ON l.attender_id = u.id AND l.effective_date BETWEEN ? AND ?
                            WHERE u.role = 'ATTENDER' AND u.active
                            GROUP BY u.id, u.display_name
                            ORDER BY final_rank, staff
                            """, first, last));

            table(workbook, styles, "Complaints",
                    List.of("Reference", "Reported", "Domain", "Room", "Complaint", "Priority", "Status", "Attender",
                            "Due", "SLA breached", "IVRS", "Resolved", "Closed", "Hours to resolve"),
                    rows("""
                            SELECT c.public_ref, %1$s AS reported, c.domain, c.reporter_room, c.raw_text, c.priority,
                                   c.status, %2$s AS attender, %3$s AS due,
                                   CASE WHEN c.sla_breached THEN 'Yes' ELSE 'No' END AS breached, c.docket_no,
                                   %4$s AS resolved, %5$s AS closed,
                                   round(extract(epoch FROM c.resolved_at - c.reported_at) / 3600.0, 1) AS hours
                            FROM complaint c WHERE c.reported_at >= ? AND c.reported_at < ?
                            ORDER BY c.reported_at
                            """.formatted(istTime("c.reported_at"), ATTENDER, istTime("c.sla_due_at"),
                                    istTime("c.resolved_at"), istTime("c.closed_at")),
                            fromUtc, toUtc));

            workbook.write(out);
            return out.toByteArray();
        } catch (IOException impossible) {
            // Writing to memory does not fail.
            throw new UncheckedIOException(impossible);
        }
    }

    /**
     * The first tab: for each domain, how many complaints came in during the period, how many are still
     * pending and how many are done, with the median repair time where enough were resolved to say.
     */
    private void dashboard(
            XSSFWorkbook workbook, Styles styles, LocalDate first, LocalDate last,
            Instant from, Instant to, LocalDateTime fromUtc, LocalDateTime toUtc) {
        XSSFSheet sheet = workbook.createSheet("Cauvery Hostel Operations " + last.getYear());
        sheet.setColumnWidth(0, 40 * 256);
        sheet.setColumnWidth(1, 12 * 256);
        cell(sheet, 0, 0, "Cauvery Hostel Operations Dashboard " + last.getYear(), styles.title);
        cell(sheet, 1, 0, "From " + first.format(DAY) + " to " + last.format(DAY), null);

        Map<String, DomainSummary> stats =
                analytics.byDomain(from, to).stream().collect(Collectors.toMap(DomainSummary::domain, Function.identity()));
        List<List<Object>> domains = rows("""
                SELECT domain, count(*) AS total,
                       count(*) FILTER (WHERE status NOT IN %1$s AND status <> 'REJECTED') AS pending,
                       count(*) FILTER (WHERE status IN %1$s) AS completed,
                       count(*) FILTER (WHERE status = 'REJECTED') AS rejected
                FROM complaint WHERE reported_at >= ? AND reported_at < ?
                GROUP BY domain
                ORDER BY CASE domain WHEN 'CIVIL' THEN 0 WHEN 'ELECTRICAL' THEN 1 WHEN 'RO_PLANT' THEN 2
                                     WHEN 'NETWORK' THEN 3 ELSE 4 END
                """.formatted(DONE), fromUtc, toUtc);

        int at = 3;
        if (domains.isEmpty()) {
            cell(sheet, at, 0, "No complaints in this period.", null);
        }
        for (List<Object> domain : domains) {
            String name = (String) domain.get(0);
            String label = label(name);
            cell(sheet, at, 0, name, styles.section(name));
            cell(sheet, at, 1, "", styles.section(name));
            at = line(sheet, at + 1, "Total " + label + " Complaints", domain.get(1), null);
            at = line(sheet, at, "Pending " + label + " Complaints", domain.get(2), styles.pending);
            at = line(sheet, at, "Completed " + label + " Complaints", domain.get(3), styles.completed);
            if (((Number) domain.get(4)).longValue() > 0) {
                at = line(sheet, at, "Rejected " + label + " Complaints", domain.get(4), null);
            }
            DomainSummary summary = stats.get(name);
            if (summary != null && summary.n() > 0) {
                at = line(sheet, at, "Median hours to fix", Math.round(summary.median() * 10) / 10.0, null);
            }
            at++;
        }
    }

    /** One label-and-number line of the dashboard; returns the next free row. */
    private static int line(XSSFSheet sheet, int at, String text, Object number, XSSFCellStyle style) {
        cell(sheet, at, 0, text, style);
        sheet.getRow(at).createCell(1).setCellValue(((Number) number).doubleValue());
        return at + 1;
    }

    private static void cell(XSSFSheet sheet, int row, int column, String text, XSSFCellStyle style) {
        Row line = sheet.getRow(row) == null ? sheet.createRow(row) : sheet.getRow(row);
        Cell cell = line.createCell(column);
        cell.setCellValue(text);
        if (style != null) {
            cell.setCellStyle(style);
        }
    }

    /**
     * One tab: a filterable header row frozen at the top, then the rows. Dates are real dates, tinted by
     * day so the days stand apart; numbers stay numbers so the sheet can sum them. A few columns are
     * highlighted by their header: Points, Final Rank, and a Pending status.
     */
    private static void table(
            XSSFWorkbook workbook, Styles styles, String name, List<String> headers, List<List<Object>> rows) {
        XSSFSheet sheet = workbook.createSheet(name);
        int[] widths = new int[headers.size()];
        Row header = sheet.createRow(0);
        for (int column = 0; column < headers.size(); column++) {
            Cell cell = header.createCell(column);
            cell.setCellValue(headers.get(column));
            cell.setCellStyle(styles.header);
            widths[column] = headers.get(column).length();
        }
        for (int index = 0; index < rows.size(); index++) {
            Row row = sheet.createRow(index + 1);
            List<Object> values = rows.get(index);
            for (int column = 0; column < values.size(); column++) {
                Object value = values.get(column);
                Cell cell = row.createCell(column);
                String heading = headers.get(column);
                if (value instanceof Date date) {
                    cell.setCellValue(date.toLocalDate());
                    cell.setCellStyle(styles.day(date.toLocalDate(), column == 0));
                } else if (value instanceof Number number) {
                    cell.setCellValue(number.doubleValue());
                } else {
                    cell.setCellValue(value.toString());
                }
                if (heading.equals("Points")) {
                    cell.setCellStyle(styles.points);
                } else if (heading.equals("Final Rank")) {
                    cell.setCellStyle(styles.rank);
                } else if (heading.equals("Status") && "Pending".equals(value)) {
                    cell.setCellStyle(styles.pending);
                }
                widths[column] = Math.max(widths[column], value instanceof Date ? 10 : value.toString().length());
            }
        }
        for (int column = 0; column < widths.length; column++) {
            sheet.setColumnWidth(column, Math.min(widths[column] + 3, 60) * 256);
        }
        sheet.createFreezePane(0, 1);
        sheet.setAutoFilter(new CellRangeAddress(0, Math.max(rows.size(), 1), 0, headers.size() - 1));
    }

    /** The cell styles, made once per workbook: a workbook holds a limited number of them. */
    private static final class Styles {

        /** Pastel tints for dates, picked by the day so the same date has the same colour on every tab. */
        private static final String[] DAY_TINTS = {"FFF2CC", "A2C4C9", "EA9999", "93C47D", "C27BA0", "BF9000", "6FA8DC"};

        private final XSSFWorkbook workbook;
        final XSSFCellStyle title;
        final XSSFCellStyle header;
        final XSSFCellStyle pending;
        final XSSFCellStyle completed;
        final XSSFCellStyle points;
        final XSSFCellStyle rank;
        private final XSSFCellStyle[] days = new XSSFCellStyle[DAY_TINTS.length];
        private final XSSFCellStyle plainDay;
        private final Map<String, XSSFCellStyle> sections;

        Styles(XSSFWorkbook workbook) {
            this.workbook = workbook;
            XSSFFont big = workbook.createFont();
            big.setBold(true);
            big.setFontHeightInPoints((short) 14);
            title = workbook.createCellStyle();
            title.setFont(big);
            XSSFFont bold = workbook.createFont();
            bold.setBold(true);
            header = filled("D9EAD3");
            header.setFont(bold);
            pending = filled("FFE599");
            completed = filled("6AA84F");
            points = filled("F6B26B");
            rank = filled("B6D7A8");
            short dateFormat = workbook.getCreationHelper().createDataFormat().getFormat("dd-mm-yyyy");
            for (int i = 0; i < DAY_TINTS.length; i++) {
                days[i] = filled(DAY_TINTS[i]);
                days[i].setDataFormat(dateFormat);
            }
            plainDay = workbook.createCellStyle();
            plainDay.setDataFormat(dateFormat);
            XSSFFont red = workbook.createFont();
            red.setColor(new XSSFColor(new byte[] {(byte) 0x99, 0, 0}, null));
            sections = Map.of(
                    "CIVIL", filled("9FC5E8"), "ELECTRICAL", filled("B4A7D6"), "RO_PLANT", filled("A2C4C9"),
                    "NETWORK", filled("F9CB9C"), "UNCLASSIFIED", filled("D9D9D9"));
            sections.values().forEach(style -> style.setFont(red));
        }

        /** The first column's date is tinted by day; any later date column is a plain date. */
        XSSFCellStyle day(LocalDate date, boolean tinted) {
            return tinted ? days[(int) (date.toEpochDay() % DAY_TINTS.length)] : plainDay;
        }

        XSSFCellStyle section(String domain) {
            return sections.getOrDefault(domain, sections.get("UNCLASSIFIED"));
        }

        private XSSFCellStyle filled(String hex) {
            XSSFCellStyle style = workbook.createCellStyle();
            byte[] rgb = {
                (byte) Integer.parseInt(hex.substring(0, 2), 16),
                (byte) Integer.parseInt(hex.substring(2, 4), 16),
                (byte) Integer.parseInt(hex.substring(4, 6), 16)
            };
            style.setFillForegroundColor(new XSSFColor(rgb, null));
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            return style;
        }
    }

    /** CIVIL as "Civil", RO_PLANT as "RO Plant", for the tab names and the dashboard lines. */
    private static String label(String domain) {
        return domain.equals("RO_PLANT") ? "RO Plant" : domain.charAt(0) + domain.substring(1).toLowerCase();
    }

    /** The attender's primary floors, e.g. "Ground floor", for the Daily Duty Updates tab. */
    private static final String FLOOR =
            """
            coalesce((SELECT string_agg(f.label, ', ' ORDER BY f.floor_number) FROM floor_assignment fa
                      JOIN floor f ON f.id = fa.floor_id WHERE fa.attender_id = u.id AND fa.coverage = 'PRIMARY'), '')""";

    /** Whoever holds or last held the complaint's task. */
    private static final String ATTENDER =
            """
            (SELECT u.display_name FROM task t
               JOIN task_assignment a ON a.task_id = t.id AND a.status <> 'RELEASED'
               JOIN app_user u ON u.id = a.attender_id
             WHERE t.complaint_id = c.id ORDER BY a.id DESC LIMIT 1)""";

    /** A stored UTC timestamp as its India date. */
    private static String istDate(String column) {
        return "CAST(%s AT TIME ZONE 'UTC' AT TIME ZONE 'Asia/Kolkata' AS date)".formatted(column);
    }

    /** A stored UTC timestamp as India time, e.g. {@code 2026-11-04 19:30}; blank when there is none. */
    private static String istTime(String column) {
        return "to_char(%s AT TIME ZONE 'UTC' AT TIME ZONE 'Asia/Kolkata', 'YYYY-MM-DD HH24:MI')".formatted(column);
    }

    /** The rows of a query, in column order. Every column needs its own name: rows are read as maps. */
    private List<List<Object>> rows(String sql, Object... arguments) {
        return jdbc.queryForList(sql, arguments).stream()
                .map(Map::values)
                .map(values -> values.stream().map(value -> value == null ? (Object) "" : value).toList())
                .toList();
    }
}
