package duty.ingestion.application;

import duty.ingestion.domain.IngestionStatus;

/**
 * What one ingestion run did — the record written to {@code ingestion_run}, handed back so the
 * manager sees the outcome rather than having to notice complaints stopped arriving (section 5.3).
 *
 * @param runId the {@code ingestion_run} row
 * @param rowsSeen rows read from the source
 * @param rowsCreated rows that became complaints
 * @param rowsSkipped rows already held, plus rows that could not be read
 * @param status {@code SUCCESS} even with malformed rows; {@code FAILED} only if the run stopped
 * @param failureReason why the run failed, or the malformed rows it survived; {@code null} if clean
 */
public record IngestionSummary(
        long runId,
        int rowsSeen,
        int rowsCreated,
        int rowsSkipped,
        IngestionStatus status,
        String failureReason) {

    public boolean failed() {
        return status == IngestionStatus.FAILED;
    }
}
