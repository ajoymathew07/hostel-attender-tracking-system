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

/** The .xlsx export: four tabs, this period's rows, and no student details in the file. */
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
    @DisplayName("the weekly workbook has its four tabs, this week's complaint and points, and no student details")
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
        String ref = complaints.findById(complaintId).orElseThrow().getPublicRef();

        byte[] file =
                mockMvc.perform(get("/api/manager/reports/weekly.xlsx").with(authentication(Fixtures.signIn(manager))))
                        .andExpect(status().isOk())
                        .andExpect(header().string("Content-Disposition", containsString("duty-report-weekly-")))
                        .andReturn()
                        .getResponse()
                        .getContentAsByteArray();

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file))) {
            assertThat(List.of(0, 1, 2, 3)).extracting(i -> workbook.getSheetName(i))
                    .containsExactly("Summary", "Leaderboard", "Points", "Complaints");
            assertThat(text(workbook.getSheet("Complaints"))).contains(ref, "214").doesNotContain("Asha Student", "9876543210");
            assertThat(text(workbook.getSheet("Points"))).contains(ravi.getDisplayName(), "Hostel night", "5.0");
            assertThat(text(workbook.getSheet("Leaderboard"))).contains(ravi.getDisplayName());
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
