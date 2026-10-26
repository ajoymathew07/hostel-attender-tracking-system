package duty.reporting.web;

import duty.reporting.application.ExcelReport.Period;
import duty.reporting.application.ExcelReport;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/manager/reports/{daily|weekly|monthly}.xlsx} (section 9.4). */
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
        String filename = "duty-report-" + period + "-" + LocalDate.now(ZoneId.of("Asia/Kolkata")) + ".xlsx";
        return ResponseEntity.ok()
                .contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .body(workbook);
    }
}
