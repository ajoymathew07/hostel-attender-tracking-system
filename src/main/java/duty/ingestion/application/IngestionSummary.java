package duty.ingestion.application;

import duty.ingestion.domain.IngestionStatus;

/**
 * The outcome of one ingestion run, shown to the manager.
 *
 * @param rowsSkipped rows already held, plus rows that could not be read
 * @param status {@code SUCCESS} even with malformed rows; {@code FAILED} only if the run stopped
 * @param failureReason why the run failed, or the malformed rows it skipped; {@code null} if clean
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
