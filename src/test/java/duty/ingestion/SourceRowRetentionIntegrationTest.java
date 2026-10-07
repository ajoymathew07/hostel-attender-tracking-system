package duty.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import duty.ingestion.domain.IngestionRun.IngestionStatus;
import duty.ingestion.domain.IngestionRun.TriggerType;
import duty.ingestion.domain.IngestionRun;
import duty.ingestion.domain.SourceRow.SourceRowState;
import duty.ingestion.domain.SourceRow.SourceType;
import duty.ingestion.domain.SourceRow;
import duty.ingestion.infrastructure.IngestionRunRepository;
import duty.ingestion.infrastructure.SourceRowRepository;
import duty.support.AbstractIntegrationTest;
import duty.support.Fixtures;
import duty.workflow.domain.Complaint;
import duty.workflow.infrastructure.ComplaintRepository;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Change detection and retention: after the payload is purged, the kept hash still recognises the row.
 */
@SpringBootTest
class SourceRowRetentionIntegrationTest extends AbstractIntegrationTest {

    @Autowired private SourceRowRepository sourceRows;
    @Autowired private IngestionRunRepository runs;
    @Autowired private ComplaintRepository complaints;

    private Long runId;

    @BeforeEach
    void startRun() {
        this.runId = runs.saveAndFlush(Fixtures.ingestionRun()).getId();
    }

    @Test
    @DisplayName("purging raw_payload keeps content_hash, so dedupe still works afterwards")
    void purgeKeepsTheHash() {
        String contentHash = Fixtures.hash(Fixtures.unique("content"));
        SourceRow row = sourceRows.saveAndFlush(row(contentHash));

        assertThat(row.getRawPayload()).isNotEmpty();

        row.purgeRawPayload();
        sourceRows.saveAndFlush(row);

        SourceRow purged = sourceRows.findById(row.getId()).orElseThrow();
        assertThat(purged.getRawPayload()).isEmpty();
        assertThat(purged.getContentHash())
                .describedAs("retention and idempotency coexist because the hash survives")
                .isEqualTo(contentHash);
        assertThat(purged.contentChangedFrom(contentHash)).isFalse();
        assertThat(purged.contentChangedFrom(Fixtures.hash("something else"))).isTrue();
    }

    @Test
    @DisplayName("linking a source row to its complaint closes the ingestion loop")
    void rowIsLinkedToItsComplaint() {
        SourceRow row = sourceRows.saveAndFlush(row(Fixtures.hash(Fixtures.unique("content"))));
        assertThat(row.getComplaintId()).isNull();
        assertThat(sourceRows.findByComplaintIdIsNull())
                .extracting(SourceRow::getId)
                .contains(row.getId());

        Complaint complaint = complaints.saveAndFlush(Fixtures.complaint());
        row.linkToComplaint(complaint.getId());
        sourceRows.saveAndFlush(row);

        SourceRow reloaded = sourceRows.findById(row.getId()).orElseThrow();
        assertThat(reloaded.getComplaintId()).isEqualTo(complaint.getId());
        assertThat(reloaded.getState()).isEqualTo(SourceRowState.ACTIVE);
        assertThat(sourceRows.findByComplaintIdIsNull())
                .extracting(SourceRow::getId)
                .doesNotContain(row.getId());
    }

    @Test
    @DisplayName("a hash that is not a 64-character digest is refused at construction")
    void hashWidthIsEnforcedInTheEntity() {
        assertThatThrownBy(
                        () ->
                                new SourceRow(
                                        SourceType.CSV_UPLOAD,
                                        "batch-1",
                                        "too-short",
                                        Fixtures.hash("content"),
                                        1,
                                        "{}",
                                        runId,
                                        Instant.parse("2026-02-01T06:03:00Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("64");

        assertThat(SourceRow.HASH_LENGTH).isEqualTo(64);
        assertThat(sourceRows.existsBySourceTypeAndSourceRefAndResponseKey(
                        SourceType.CSV_UPLOAD, "batch-never-seen", Fixtures.hash("nope")))
                .isFalse();
    }

    @Test
    @DisplayName("a failed run records its reason loudly rather than passing silently — E12")
    void failedRunKeepsItsErrorDetail() {
        IngestionRun run =
                runs.saveAndFlush(
                        new IngestionRun(
                                SourceType.CSV_UPLOAD,
                                TriggerType.MANUAL,
                                Instant.parse("2026-02-01T06:00:00Z"),
                                null));

        assertThat(run.isFinished()).isFalse();
        assertThat(run.getStatus()).isEqualTo(IngestionStatus.RUNNING);
        assertThat(run.getFinishedAt()).isNull();
        assertThat(run.getErrorDetail()).isNull();

        run.fail(Instant.parse("2026-02-01T06:00:05Z"), "INGESTION_COLUMN_MISSING: 'Room No'");
        runs.saveAndFlush(run);

        IngestionRun reloaded = runs.findById(run.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(IngestionStatus.FAILED);
        assertThat(reloaded.isFinished()).isTrue();
        assertThat(reloaded.getFinishedAt()).isEqualTo(Instant.parse("2026-02-01T06:00:05Z"));
        assertThat(reloaded.getErrorDetail()).contains("INGESTION_COLUMN_MISSING");
        assertThat(reloaded.getSourceType()).isEqualTo(SourceType.CSV_UPLOAD);
        assertThat(reloaded.getTriggerType()).isEqualTo(TriggerType.MANUAL);
        assertThat(reloaded.getTriggeredBy()).isNull();
        assertThat(runs.findByStatusOrderByStartedAtDesc(IngestionStatus.FAILED))
                .extracting(IngestionRun::getId)
                .contains(run.getId());
        assertThat(runs.findTop20ByOrderByStartedAtDesc()).isNotEmpty();
    }

    private SourceRow row(String contentHash) {
        return new SourceRow(
                SourceType.GOOGLE_SHEETS,
                "sheet-cauvery-2026",
                Fixtures.hash(Fixtures.unique("response")),
                contentHash,
                1,
                "{\"timestamp\":\"2026-02-01\",\"text\":\"no water\"}",
                runId,
                Instant.parse("2026-02-01T06:03:00Z"));
    }
}
