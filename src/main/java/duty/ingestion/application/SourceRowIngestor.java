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
 * Ingests one source row: the only place ingestion creates a complaint.
 *
 * <p>Each row runs in its own transaction ({@code REQUIRES_NEW}) so one bad row cannot abort the run.
 * A separate bean because that annotation is ignored on self-invocation.
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
     * Ingests one row, or recognises that it is already held.
     *
     * @throws MalformedRowException if the row cannot be normalized; the caller records it and continues
     * @throws org.springframework.dao.DataIntegrityViolationException if a concurrent run inserted the
     *     same response first
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
