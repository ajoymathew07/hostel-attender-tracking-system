package duty.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import duty.ingestion.application.CsvUploadService;
import duty.ingestion.application.IngestionSummary;
import duty.ingestion.application.SheetsIngestionService;
import duty.ingestion.application.UploadedFile;
import duty.ingestion.infrastructure.IngestionRunRepository;
import duty.support.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;

/**
 * The state every developer machine and every CI run is in: <strong>no service-account key</strong>
 * (section 11.5).
 *
 * <p>This is the test that keeps the key out of the repository honest. The key is an environment
 * variable and nothing else, so if its absence broke startup there would be constant pressure to
 * commit a file "just for tests" — which is exactly the failure section 11.5 exists to prevent.
 *
 * <p>So: the context starts, the CSV fallback ingests normally, and "Sync Now" declines rather than
 * throwing. Nothing in this class sets {@code ingestion.sheets.*}, which is the point.
 */
@SpringBootTest
@WithMockUser(roles = "MANAGER")
class SheetsAbsentCredentialIntegrationTest extends AbstractIntegrationTest {

    @Autowired private SheetsIngestionService sheets;
    @Autowired private CsvUploadService uploads;
    @Autowired private IngestionRunRepository runs;

    @Test
    @DisplayName("without a key the application is up, and Sync Now declines instead of failing")
    void withoutAKeySyncDeclines() {
        long runsBefore = runs.count();

        assertThat(sheets.configured()).isFalse();
        assertThat(sheets.syncNow()).isEmpty();

        // It declined before opening a run: an unconfigured instance has nothing to report.
        assertThat(runs.count()).isEqualTo(runsBefore);
    }

    @Test
    @DisplayName("and the startup catch-up is a no-op rather than a failed run on every boot")
    void withoutAKeyTheStartupCatchUpDoesNothing() {
        long runsBefore = runs.count();

        // Section 2.5's third mitigation runs on every boot, including the boot of a machine that
        // has no sheet. If that recorded a failure it would raise the degraded-ingestion banner on
        // an instance where CSV is the intended and only path, and the banner would mean nothing.
        assertThat(sheets.catchUp()).isEmpty();
        assertThat(runs.count()).isEqualTo(runsBefore);
    }

    @Test
    @DisplayName("and the CSV path — the other half of the same port — still creates complaints")
    void theCsvPathStillWorks() {
        IngestionSummary summary =
                uploads.upload(
                        new UploadedFile(
                                "responses.csv",
                                "text/csv",
                                "Timestamp,Describe the issue,Room Number\n2026/10/01 06:00:00,No key needed for this,808\n"
                                        .getBytes(StandardCharsets.UTF_8)));

        assertThat(summary.failed()).isFalse();
        assertThat(summary.rowsCreated()).isEqualTo(1);
    }
}
