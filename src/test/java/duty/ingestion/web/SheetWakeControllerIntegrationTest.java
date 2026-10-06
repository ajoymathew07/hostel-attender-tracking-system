package duty.ingestion.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import duty.ingestion.domain.IngestionRun;
import duty.ingestion.domain.TriggerType;
import duty.ingestion.infrastructure.IngestionRunRepository;
import duty.ingestion.infrastructure.SheetReader;
import duty.support.AbstractIntegrationTest;
import duty.workflow.infrastructure.ComplaintRepository;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The Apps Script wake endpoint: opens for the right secret, refuses everyone else with a 403 (not a
 * login redirect), and a repeated call changes nothing.
 *
 * <p>Requests are anonymous with no CSRF token, like the real caller. The secret is a test value.
 */
@SpringBootTest(
        properties = {
            "ingestion.sheets.spreadsheet-id=1SyntheticSheetIdForTheWakeTrigger",
            "ingestion.sheets.credentials=stubbed-in-tests",
            "ingestion.sheets.wake-secret=" + SheetWakeControllerIntegrationTest.SECRET,
            // Long enough that the background poll cannot fire mid-test.
            "ingestion.sheets.poll-interval=PT1H"
        })
@AutoConfigureMockMvc
@WithAnonymousUser
class SheetWakeControllerIntegrationTest extends AbstractIntegrationTest {

    static final String SECRET = "a-test-only-wake-secret";

    private static final String PATH = "/api/ingestion/wake";
    private static final String HEADER = "X-Wake-Secret";

    /** Runs the background task on the calling thread so the test does not race it. */
    @TestConfiguration
    static class RunTheHandoffInline {

        @Bean
        TaskExecutor applicationTaskExecutor() {
            return new SyncTaskExecutor();
        }
    }

    @MockBean private SheetReader reader;

    @Autowired private MockMvc mockMvc;
    @Autowired private ComplaintRepository complaints;
    @Autowired private IngestionRunRepository runs;

    private void sheetHolds(List<Object>... responses) {
        List<List<Object>> grid = new ArrayList<>();
        grid.add(List.of("Timestamp", "Email Address", "Room Number", "Describe the issue"));
        grid.addAll(List.of(responses));
        given(reader.readValues(anyString(), anyString())).willReturn(grid);
    }

    @Test
    @DisplayName("the trigger wakes the instance, is accepted, and the sheet's rows become complaints")
    void aTriggerWithTheSecretIngestsTheSheet() throws Exception {
        sheetHolds(List.of("2026/09/01 08:00:00", "wake@example.com", "301", "No water in the bathroom"));
        long before = complaints.count();

        mockMvc.perform(post(PATH).header(HEADER, SECRET)).andExpect(status().isAccepted());

        assertThat(complaints.count()).isEqualTo(before + 1);
        // The data model records the trigger as MANUAL — "the manager's Sync Now button, or the
        // Apps Script trigger waking the instance" (section 3.5).
        assertThat(runs.findTop20ByOrderByStartedAtDesc())
                .extracting(IngestionRun::getTriggerType)
                .contains(TriggerType.MANUAL);
    }

    @Test
    @DisplayName("a duplicate trigger creates no duplicate complaint — the watermark still decides")
    void aDuplicateTriggerCreatesNothing() throws Exception {
        sheetHolds(List.of("2026/09/02 08:00:00", "twice@example.com", "302", "Fan making a noise"));

        mockMvc.perform(post(PATH).header(HEADER, SECRET)).andExpect(status().isAccepted());
        long afterFirst = complaints.count();

        // Apps Script retries a failed fetch, and two responses in quick succession fire the
        // trigger twice. Section 2.5 leans on this being harmless: the request carries no row
        // data, so the second call recomputes the same response keys and skips every row.
        mockMvc.perform(post(PATH).header(HEADER, SECRET)).andExpect(status().isAccepted());

        assertThat(complaints.count()).isEqualTo(afterFirst);
    }

    @Test
    @DisplayName("a trigger with no secret at all is refused, and ingests nothing")
    void aTriggerWithoutTheSecretIsRefused() throws Exception {
        sheetHolds(List.of("2026/09/03 08:00:00", "nosecret@example.com", "303", "Light not working"));
        long before = complaints.count();

        mockMvc.perform(post(PATH)).andExpect(status().isForbidden());

        assertThat(complaints.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("a trigger with the wrong secret is refused, and ingests nothing")
    void aTriggerWithTheWrongSecretIsRefused() throws Exception {
        sheetHolds(List.of("2026/09/04 08:00:00", "wrong@example.com", "304", "Tap leaking"));
        long before = complaints.count();

        mockMvc.perform(post(PATH).header(HEADER, SECRET + "x")).andExpect(status().isForbidden());
        mockMvc.perform(post(PATH).header(HEADER, "")).andExpect(status().isForbidden());

        assertThat(complaints.count()).isEqualTo(before);
    }
}
