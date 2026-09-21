package in.ac.iitm.hostel.duty.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.ac.iitm.hostel.duty.ingestion.domain.IngestionRun;
import in.ac.iitm.hostel.duty.ingestion.domain.SourceRow;
import in.ac.iitm.hostel.duty.ingestion.domain.SourceType;
import in.ac.iitm.hostel.duty.ingestion.infrastructure.IngestionRunRepository;
import in.ac.iitm.hostel.duty.ingestion.infrastructure.SourceRowRepository;
import in.ac.iitm.hostel.duty.support.AbstractIntegrationTest;
import in.ac.iitm.hostel.duty.support.Fixtures;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * {@code uq_source_row} — the invariant the ingestion module owns: a source row is never turned
 * into two complaints (architecture section 2.3, data model section 3.5).
 *
 * <p>Identity is {@code (source_type, source_ref, response_key)}, and {@code response_key} is a
 * stable hash of the submission rather than the row's position, which is what makes the corner
 * cases in test plan E2/E3/E9/E11 come out right.
 */
@SpringBootTest
class SourceRowUniquenessIntegrationTest extends AbstractIntegrationTest {

    private static final String SHEET = "sheet-cauvery-2026";

    @Autowired private SourceRowRepository sourceRows;
    @Autowired private IngestionRunRepository runs;

    private Long runId;
    private Long laterRunId;

    @BeforeEach
    void startRuns() {
        this.runId = runs.saveAndFlush(Fixtures.ingestionRun()).getId();
        this.laterRunId = runs.saveAndFlush(Fixtures.ingestionRun()).getId();
    }

    @Test
    @DisplayName("uq_source_row rejects the same (source, ref, response_key) twice — E3, the byte-identical resubmission")
    void duplicateResponseKeyIsRejected() {
        String responseKey = Fixtures.hash(Fixtures.unique("response"));

        sourceRows.saveAndFlush(row(responseKey, Fixtures.hash("content-a"), 1));

        assertThatThrownBy(
                        () -> sourceRows.saveAndFlush(row(responseKey, Fixtures.hash("content-a"), 1)))
                .describedAs(
                        "a second observation of the same source row must be rejected by the"
                                + " database, so it can never become a second complaint")
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(
                        sourceRows.findBySourceTypeAndSourceRefAndResponseKey(
                                SourceType.GOOGLE_SHEETS, SHEET, responseKey))
                .describedAs("exactly one row survives")
                .isPresent();
    }

    @Test
    @DisplayName("a re-ingested row whose content changed still collides — the key is identity, the hash is not")
    void changedContentUnderTheSameResponseKeyStillCollides() {
        String responseKey = Fixtures.hash(Fixtures.unique("response"));
        sourceRows.saveAndFlush(row(responseKey, Fixtures.hash("original"), 1));

        // E10: the sheet row was edited after ingestion. It is the same submission, so it must not
        // produce a second row — the edit is skipped and logged, and the original text is retained.
        assertThatThrownBy(
                        () -> sourceRows.saveAndFlush(row(responseKey, Fixtures.hash("edited"), 1)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("two residents submitting identical text are two rows — distinct response keys — E2")
    void identicalTextFromDifferentResidentsIsTwoRows() {
        String contentHash = Fixtures.hash("no water on the second floor");

        sourceRows.saveAndFlush(row(Fixtures.hash(Fixtures.unique("resident-a")), contentHash, 1));
        sourceRows.saveAndFlush(row(Fixtures.hash(Fixtures.unique("resident-b")), contentHash, 2));

        assertThat(sourceRows.findByContentHash(contentHash))
                .describedAs("identical wording is not identity; the response key is")
                .hasSize(2);
    }

    @Test
    @DisplayName("the same response key under a different source is a different row")
    void theSourceIsPartOfIdentity() {
        String responseKey = Fixtures.hash(Fixtures.unique("response"));

        sourceRows.saveAndFlush(row(responseKey, Fixtures.hash("content"), 1));
        SourceRow fromCsv =
                new SourceRow(
                        SourceType.CSV_UPLOAD,
                        SHEET,
                        responseKey,
                        Fixtures.hash("content"),
                        1,
                        "{}",
                        runId,
                        Instant.parse("2026-02-01T06:03:00Z"));

        assertThat(sourceRows.saveAndFlush(fromCsv).getId()).isNotNull();
    }

    @Test
    @DisplayName("row_index is a hint: a deleted row shifting every index produces no new row — E11")
    void rowIndexIsNotIdentity() {
        String responseKey = Fixtures.hash(Fixtures.unique("response"));
        SourceRow first = sourceRows.saveAndFlush(row(responseKey, Fixtures.hash("content"), 17));

        // The row above it was deleted, so this submission now sits at index 16.
        assertThatThrownBy(
                        () -> sourceRows.saveAndFlush(row(responseKey, Fixtures.hash("content"), 16)))
                .describedAs("position moved, identity did not")
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(first.getRowIndex()).isEqualTo(17);
    }

    @Test
    @DisplayName("a later run seeing the same row moves the watermark without creating a row")
    void reobservationUpdatesTheWatermarkInPlace() {
        String responseKey = Fixtures.hash(Fixtures.unique("response"));
        SourceRow stored = sourceRows.saveAndFlush(row(responseKey, Fixtures.hash("content"), 1));
        Long id = stored.getId();

        stored.seenAgain(laterRunId, Instant.parse("2026-02-01T06:06:00Z"));
        sourceRows.saveAndFlush(stored);

        SourceRow reloaded = sourceRows.findById(id).orElseThrow();
        assertThat(reloaded.getFirstSeenRunId()).isEqualTo(runId);
        assertThat(reloaded.getLastSeenRunId()).isEqualTo(laterRunId);
        assertThat(reloaded.getLastSeenAt()).isEqualTo(Instant.parse("2026-02-01T06:06:00Z"));
        assertThat(reloaded.getRevision()).isEqualTo(1);
    }

    @Test
    @DisplayName("the ingestion run tallies what it saw, and the FK to it holds")
    void ingestionRunRecordsItsTally() {
        IngestionRun run = runs.findById(runId).orElseThrow();
        run.countRow(true);
        run.countRow(false);
        run.succeed(Instant.parse("2026-02-01T06:03:30Z"));
        runs.saveAndFlush(run);

        IngestionRun reloaded = runs.findById(runId).orElseThrow();
        assertThat(reloaded.getRowsSeen()).isEqualTo(2);
        assertThat(reloaded.getRowsCreated()).isEqualTo(1);
        assertThat(reloaded.getRowsSkipped()).isEqualTo(1);
        assertThat(reloaded.isFinished()).isTrue();
    }

    private SourceRow row(String responseKey, String contentHash, Integer rowIndex) {
        return new SourceRow(
                SourceType.GOOGLE_SHEETS,
                SHEET,
                responseKey,
                contentHash,
                rowIndex,
                "{\"timestamp\":\"2026-02-01\"}",
                runId,
                Instant.parse("2026-02-01T06:03:00Z"));
    }
}
