package duty.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsString;

import duty.identity.domain.AppUser;
import duty.identity.infrastructure.AppUserRepository;
import duty.support.AbstractIntegrationTest;
import duty.support.Fixtures;
import duty.workflow.infrastructure.ComplaintRepository;
import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

/** The .xlsx export: the hostel register's nine tabs, this period's rows, and no student details in the file. */
@SpringBootTest
@AutoConfigureMockMvc
class ExcelReportIntegrationTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private AppUserRepository users;
    @Autowired private ComplaintRepository complaints;
    @Autowired private JdbcTemplate jdbc;

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("the weekly workbook has the register's tabs, this week's complaint, duties and penalty, and no student details")
    void theWeeklyWorkbook() throws Exception {
        AppUser manager = users.saveAndFlush(Fixtures.manager());
        AppUser ravi = users.saveAndFlush(Fixtures.attender());
        long complaintId = complaints.saveAndFlush(Fixtures.complaint()).getId();
        jdbc.update(
                "UPDATE complaint SET reported_at = ?, reporter_room = '214', reporter_name = 'Asha Student',"
                        + " reporter_phone = '9876543210' WHERE id = ?",
                LocalDateTime.now(ZoneOffset.UTC).minusHours(1),
                complaintId);
        jdbc.update(
                "INSERT INTO point_ledger (attender_id, reason_code, points, effective_date, period_key,"
                        + " idempotency_key, created_by, created_at, note)"
                        + " VALUES (?, 'MANUAL_ADJUSTMENT', 5, ?, ?, ?, ?, now(), 'Hostel night')",
                ravi.getId(),
                LocalDate.now(ZoneId.of("Asia/Kolkata")),
                LocalDate.now(ZoneId.of("Asia/Kolkata")).toString().substring(0, 7),
                Fixtures.unique("ADJ"),
                manager.getId());
        jdbc.update(
                "INSERT INTO point_ledger (attender_id, reason_code, points, effective_date, period_key,"
                        + " idempotency_key, created_by, created_at, note)"
                        + " VALUES (?, 'PENALTY', -1, ?, ?, ?, ?, now(), 'Came in late')",
                ravi.getId(),
                LocalDate.now(ZoneId.of("Asia/Kolkata")),
                LocalDate.now(ZoneId.of("Asia/Kolkata")).toString().substring(0, 7),
                Fixtures.unique("PEN"),
                manager.getId());
        long dutyId = jdbc.queryForObject(
                "INSERT INTO task (task_type, title, point_value, status, created_by, created_at)"
                        + " VALUES ('SPECIAL_DUTY', 'RO plant log morning', 1, 'COMPLETED', ?, now()) RETURNING id",
                Long.class,
                manager.getId());
        jdbc.update(
                "INSERT INTO task_assignment (task_id, attender_id, assign_method, assigned_at, status)"
                        + " VALUES (?, ?, 'CLAIMED', now(), 'COMPLETED')",
                dutyId,
                ravi.getId());
        jdbc.update(
                "INSERT INTO point_ledger (attender_id, task_id, reason_code, points, effective_date, period_key,"
                        + " idempotency_key, created_by, created_at)"
                        + " VALUES (?, ?, 'SPECIAL_DUTY', 1, ?, ?, ?, ?, now())",
                ravi.getId(),
                dutyId,
                LocalDate.now(ZoneId.of("Asia/Kolkata")),
                LocalDate.now(ZoneId.of("Asia/Kolkata")).toString().substring(0, 7),
                Fixtures.unique("DUTY"),
                manager.getId());
        String ref = complaints.findById(complaintId).orElseThrow().getPublicRef();

        byte[] file =
                mockMvc.perform(get("/api/manager/reports/weekly.xlsx").with(authentication(Fixtures.signIn(manager))))
                        .andExpect(status().isOk())
                        .andExpect(header().string("Content-Disposition", containsString("duty-report-weekly-")))
                        .andReturn()
                        .getResponse()
                        .getContentAsByteArray();

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file))) {
            String year = String.valueOf(LocalDate.now(ZoneId.of("Asia/Kolkata")).getYear());
            List<String> names = new ArrayList<>();
            workbook.forEach(sheet -> names.add(sheet.getSheetName()));
            assertThat(names).containsExactly(
                    "Cauvery Hostel Operations " + year, "Daily Duty Updates", "Electrical Complaints",
                    "Civil Complaints", "RO Plant Log", "Manager Special Duty", "Attendance & Discipline",
                    "Leaderboard", "Complaints");
            assertThat(text(workbook.getSheet("Complaints")))
                    .contains(ref, "214", "No water in the bathroom since morning", "RO_PLANT", "IVRS")
                    .doesNotContain("Asha Student", "9876543210");
            assertThat(text(workbook.getSheetAt(0)))
                    .contains("RO_PLANT", "Total RO Plant Complaints", "Pending RO Plant Complaints", "1.0");
            assertThat(text(workbook.getSheet("Daily Duty Updates")))
                    .contains(ravi.getDisplayName(), "Hostel night", "RO plant log morning", "5.0")
                    .doesNotContain("Came in late");
            assertThat(text(workbook.getSheet("RO Plant Log"))).contains("DONE", ravi.getDisplayName());
            assertThat(text(workbook.getSheet("Manager Special Duty")))
                    .contains("RO plant log morning", ravi.getDisplayName(), "yes");
            assertThat(text(workbook.getSheet("Attendance & Discipline")))
                    .contains(ravi.getDisplayName(), "Came in late", "Yes", "-1.0");
            // Earned 5 + 1, penalty -1, so a final score of 5 at rank 1.
            Sheet leaderboard = workbook.getSheet("Leaderboard");
            Row raviRow = leaderboard.getRow(1);
            assertThat(List.of(0, 1, 2, 3, 4)).extracting(i -> raviRow.getCell(i).toString())
                    .containsExactly(ravi.getDisplayName(), "6.0", "-1.0", "5.0", "1.0");
        }
    }

    @Test
    @DisplayName("an attender cannot download the export")
    void anAttenderIsRefused() throws Exception {
        var asAttender = Fixtures.signIn(users.saveAndFlush(Fixtures.attender()));
        mockMvc.perform(get("/api/manager/reports/monthly.xlsx").with(authentication(asAttender)))
                .andExpect(status().isForbidden());
    }

    private static List<String> text(Sheet sheet) {
        List<String> cells = new ArrayList<>();
        for (Row row : sheet) {
            for (Cell cell : row) {
                cells.add(cell.toString());
            }
        }
        return cells;
    }
}
