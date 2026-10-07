package duty.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import duty.ingestion.application.IngestionPipeline.IngestionSummary;
import duty.ingestion.application.SheetsIngestionService.IngestionHealth;
import duty.ingestion.application.SheetsIngestionService;
import duty.ingestion.domain.IngestionRun.TriggerType;
import duty.ingestion.infrastructure.GoogleSheetsSource.SheetReader;
import duty.ingestion.infrastructure.GoogleSheetsSource.SheetsUnavailableException;
import duty.ingestion.infrastructure.IngestionRunRepository;
import duty.ingestion.infrastructure.SheetsPollScheduler;
import duty.support.AbstractIntegrationTest;
import duty.workflow.infrastructure.ComplaintRepository;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * While the Sheets API is failing: polling backs off, the dashboard shows a banner, and everything is
 * caught up once it recovers.
 *
 * <p>{@code scheduler.poll()} is a scheduler tick (which the backoff may skip); {@code sheets.poll()}
 * is a run, used to simulate a tick after the backoff has passed without waiting.
 */
@SpringBootTest(
        properties = {
            "ingestion.sheets.spreadsheet-id=1SyntheticSheetIdForDegradation",
            "ingestion.sheets.credentials=stubbed-in-tests",
            "ingestion.sheets.poll-interval=PT1H"
        })
@AutoConfigureMockMvc
class IngestionDegradationIntegrationTest extends AbstractIntegrationTest {

    private static final String SHEET_ID = "1SyntheticSheetIdForDegradation";

    private static final List<Object> HEADERS =
            List.of("Timestamp", "Email Address", "Room Number", "Describe the issue");

    @MockBean private SheetReader reader;

    @Autowired private SheetsIngestionService sheets;
    @Autowired private SheetsPollScheduler scheduler;
    @Autowired private IngestionHealth health;
    @Autowired private IngestionRunRepository runs;
    @Autowired private ComplaintRepository complaints;
    @Autowired private MockMvc mockMvc;

    private void sheetHolds(List<Object>... responses) {
        List<List<Object>> grid = new ArrayList<>();
        grid.add(HEADERS);
        grid.addAll(List.of(responses));
        // The `willReturn(...).given(mock)` form, not `given(mock.method())`: this test moves a
        // stub from throwing back to returning, and the other form would call the still-throwing
        // stub for real while setting up the new one.
        willReturn(grid).given(reader).readValues(anyString(), anyString());
    }

    private void sheetIsDown() {
        willThrow(new SheetsUnavailableException(SHEET_ID, new IOException("Google is unreachable")))
                .given(reader)
                .readValues(anyString(), anyString());
    }

    /** Each test starts healthy; a successful run is what resets the counter. */
    @BeforeEach
    void startHealthy() {
        sheetHolds();
        sheets.poll();
        assertThat(health.degraded()).isFalse();
        assertThat(health.consecutiveFailures()).isZero();
    }

    @Test
    @DisplayName("boot reconciles once, and an unreadable sheet cannot take startup down with it")
    void bootReconcilesOnceAndCannotTakeStartupDown() {
        // The listener fired while this context was starting — against a stub that had not been
        // told what to return, which is as unreachable as a sheet gets. The run is on record and
        // the context is up, which is the whole of the claim (section 2.5, point 3).
        assertThat(runs.findAll())
                .anyMatch(run -> run.getTriggerType() == TriggerType.STARTUP && run.isFinished());

        // And the same call again with the sheet openly failing: a summary, never an exception.
        // A catch-up that could throw would be a catch-up that could refuse to boot.
        sheetIsDown();
        IngestionSummary summary = sheets.catchUp().orElseThrow();

        assertThat(summary.failed()).isTrue();
        assertThat(summary.failureReason()).contains(SHEET_ID);
    }

    @Test
    @DisplayName("one failure retries on the usual cadence; from the second the poll backs off")
    void thePollBacksOffOnRepeatedFailure() {
        sheetIsDown();

        // First failure. Section 5.3's first rung is three minutes, which is the poll interval
        // anyway, so there is no delay for the backoff to add and the next tick runs as usual.
        sheets.poll();
        assertThat(health.backingOff()).isFalse();

        // Second. The ladder moves to six minutes, which is longer than the cadence, so ticks in
        // between are now skipped rather than spent on an API that is not answering.
        sheets.poll();
        assertThat(health.backingOff()).isTrue();

        long runsBeforeTheSkippedTick = runs.count();
        scheduler.poll();

        assertThat(runs.count())
                .as("a skipped tick opens no run, so it costs no quota")
                .isEqualTo(runsBeforeTheSkippedTick);
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    @DisplayName("three consecutive failures put the degraded banner on the manager's dashboard")
    void threeFailuresRaiseTheDegradedBanner() throws Exception {
        sheetIsDown();
        sheets.poll();
        sheets.poll();

        // Two is not yet three: the banner must not cry wolf at the first blip, or the manager
        // learns to ignore it.
        assertThat(health.degraded()).isFalse();
        mockMvc.perform(get("/complaints"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.not(Matchers.containsString("Complaints may not be arriving"))));

        sheets.poll();

        assertThat(health.degraded()).isTrue();
        mockMvc.perform(get("/complaints"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Complaints may not be arriving")))
                .andExpect(content().string(Matchers.containsString("3 times in a row")))
                // It tells the manager what to do, which is the point of section 5.3's banner.
                .andExpect(content().string(Matchers.containsString("Export the sheet as CSV")));
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    @DisplayName("on recovery the run catches up everything that arrived during the outage, and the banner clears")
    void recoveryCatchesUpAndClearsTheBanner() throws Exception {
        sheetIsDown();
        sheets.poll();
        sheets.poll();
        sheets.poll();
        assertThat(health.degraded()).isTrue();

        // Six hours of responses landed while the API was refusing us. The watermark never moved,
        // so the very next ordinary run covers all of them — there is no catch-up mode to enter
        // and none to get wrong (section 5.3).
        long before = complaints.count();
        sheetHolds(
                List.of("2026/09/10 06:00:00", "outage1@example.com", "501", "No water on the fifth floor"),
                List.of("2026/09/10 09:30:00", "outage2@example.com", "502", "Ceiling fan sparking"));

        IngestionSummary recovered = sheets.poll().orElseThrow();

        assertThat(recovered.failed()).isFalse();
        assertThat(recovered.rowsCreated()).isEqualTo(2);
        assertThat(complaints.count()).isEqualTo(before + 2);
        assertThat(health.degraded()).isFalse();
        assertThat(health.consecutiveFailures()).isZero();

        mockMvc.perform(get("/complaints"))
                .andExpect(content().string(Matchers.not(Matchers.containsString("Complaints may not be arriving"))));
    }
}
