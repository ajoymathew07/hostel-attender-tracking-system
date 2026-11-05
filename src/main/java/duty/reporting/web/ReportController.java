package duty.reporting.web;

import duty.reporting.application.ExcelReport.Period;
import duty.reporting.application.ExcelReport;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@code GET /api/manager/reports/{daily|weekly|monthly}.xlsx} (section 9.4), and
 * {@code GET /api/manager/reports/range.xlsx?from=YYYY-MM-DD&to=YYYY-MM-DD} for any other stretch of days.
 */
@RestController
class ReportController {

    private static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final ExcelReport report;

    ReportController(ExcelReport report) {
        this.report = report;
    }

    @GetMapping("/api/manager/reports/{period:daily|weekly|monthly}.xlsx")
    ResponseEntity<byte[]> download(@PathVariable String period) {
        byte[] workbook = report.build(Period.valueOf(period.toUpperCase(Locale.ROOT)));
        return file(workbook, "duty-report-" + period + "-" + LocalDate.now(ZoneId.of("Asia/Kolkata")) + ".xlsx");
    }

    /** Both days are included, so from = to gives that one day. */
    @GetMapping("/api/manager/reports/range.xlsx")
    ResponseEntity<byte[]> downloadRange(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        if (from.isAfter(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The start date is after the end date");
        }
        return file(report.build(from, to), "duty-report-" + from + "-to-" + to + ".xlsx");
    }

    private static ResponseEntity<byte[]> file(byte[] workbook, String filename) {
        return ResponseEntity.ok()
                .contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .body(workbook);
    }
}
