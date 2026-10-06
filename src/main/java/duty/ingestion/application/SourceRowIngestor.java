package duty.ingestion.application;

import duty.ingestion.domain.MalformedRowException;
import duty.ingestion.domain.NormalizedRow;
import duty.ingestion.domain.RawRow;
import duty.ingestion.domain.RowDigest;
import duty.ingestion.domain.RowNormalizer;
import duty.ingestion.domain.SourceRow;
import duty.ingestion.domain.SourceType;
import duty.ingestion.infrastructure.SourceRowRepository;
import duty.workflow.application.ComplaintIntake;
import duty.workflow.application.IngestedComplaint;
import duty.workflow.application.NewComplaint;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reconciles one source row — section 5.3's inner loop, and the only place a complaint is created
 * from ingestion.
 *
 * <p><strong>Its own transaction, deliberately.</strong> Section 5.3 requires each row to be
 * processed independently so that one malformed response cannot abort the whole run. That is also
 * why this is a separate bean from {@link DefaultCsvUploadService} rather than a private method on
 * it: a self-invocation would not pass through the proxy, and {@code REQUIRES_NEW} would silently
 * do nothing.
 *
 * <p>This is shared by every source. The Sheets adapter of PR 8 reconciles through this same
 * class, which is the point of the port (section 5.8).
 */
@Service
class SourceRowIngestor {

    private final SourceRowRepository sourceRows;
    private final ComplaintIntake complaints;
    private final RowNormalizer normalizer;
    private final RawPayloads payloads;

    SourceRowIngestor(
            SourceRowRepository sourceRows,
            ComplaintIntake complaints,
            RowNormalizer normalizer,
            RawPayloads payloads) {
        this.sourceRows = sourceRows;
        this.complaints = complaints;
        this.normalizer = normalizer;
        this.payloads = payloads;
    }

    /**
     * Ingests one row, or recognises that we already hold it.
     *
     * @throws MalformedRowException if the row cannot be normalized — this transaction rolls back
     *     and the caller records it on the run and carries on
     * @throws org.springframework.dao.DataIntegrityViolationException if a concurrent run inserted
     *     the same response first; {@code uq_source_row}, not this method, is the guarantee
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RowOutcome ingest(RawRow row, String responseKey, SourceType sourceType, String sourceRef, long runId, Instant observedAt) {
        String contentHash = RowDigest.contentHash(row.cells());

        Optional<SourceRow> alreadyHeld =
                sourceRows.findBySourceTypeAndSourceRefAndResponseKey(sourceType, sourceRef, responseKey);
        if (alreadyHeld.isPresent()) {
            // Known key. Same content: already ingested. Different content: the response was
            // edited, which is a Phase 5 feature (section 5.3) — skipped either way, and the
            // complaint keeps its original text, which is the safe behaviour.
            SourceRow held = alreadyHeld.get();
            held.seenAgain(runId, observedAt);
            sourceRows.saveAndFlush(held);
            return RowOutcome.SKIPPED;
        }

        // Normalize before inserting, so a malformed row costs no row at all.
        NormalizedRow normalized = normalizer.normalize(row);

        // The insert is the real dedupe. The lookup above is only a fast path: check-then-insert
        // is itself a race, and `uq_source_row` is what actually makes "a source row is never
        // turned into two complaints" true.
        SourceRow sourceRow =
                sourceRows.saveAndFlush(
                        new SourceRow(
                                sourceType,
                                sourceRef,
                                responseKey,
                                contentHash,
                                row.rowIndex(),
                                payloads.toJson(row),
                                runId,
                                observedAt));

        IngestedComplaint complaint =
                complaints.record(
                        new NewComplaint(
                                normalized.rawText(),
                                normalized.reportedAt(),
                                normalized.reporterName(),
                                normalized.reporterRollNo(),
                                normalized.reporterPhone(),
                                normalized.reporterContact(),
                                normalized.reporterRoom(),
                                normalized.reportedDomain(),
                                sourceRow.getId()));

        sourceRow.linkToComplaint(complaint.id());
        sourceRows.saveAndFlush(sourceRow);
        return RowOutcome.CREATED;
    }
}
