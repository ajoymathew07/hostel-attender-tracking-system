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
 * The Apps Script wake-up trigger at the HTTP edge (sections 2.5, 11.6).
 *
 * <p>Everything asserted here is about the <em>door</em>, because the room behind it is already
 * tested: the reconciliation the trigger starts is the same one "Sync Now" and the poll start, and
 * it is covered in {@code SheetsIngestionIntegrationTest}. What is new and therefore what is
 * tested is that the door opens for Google, stays shut for everybody else, and that going through
 * it twice changes nothing.
 *
 * <p>Every request here is <strong>anonymous and carries no CSRF token</strong>, which is the
 * caller this endpoint actually has. A rejection must come back as {@code 403} from the
 * controller, not as a redirect to the login page: a redirect would mean the sign-in filter had
 * caught the call, and a Google server cannot log in.
 *
 * <p>The secret below is a test fixture, not a credential — the real one is an environment
 * variable and appears nowhere in this repository (section 11.5).
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

    /**
     * The endpoint answers before the ingestion finishes, which is the point of it — so the test
     * would otherwise be racing the executor. Running the handed-off task on the calling thread
     * makes the assertion deterministic without changing what is under test: the controller still
     * hands the run to a {@link TaskExecutor} and still answers {@code 202}.
     */
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
