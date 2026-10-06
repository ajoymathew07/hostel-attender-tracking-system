package duty.ingestion.application;

import duty.audit.application.CorrelationId;
import duty.identity.application.CurrentUser;
import duty.ingestion.domain.ComplaintSource;
import duty.ingestion.domain.FetchWindow;
import duty.ingestion.domain.MalformedRowException;
import duty.ingestion.domain.MissingRequiredColumnException;
import duty.ingestion.domain.RawRow;
import duty.ingestion.domain.RowDigest;
import duty.ingestion.domain.SourceField;
import duty.ingestion.domain.TriggerType;
import duty.ingestion.domain.UploadRejectedException;
import duty.ingestion.infrastructure.SheetsUnavailableException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Section 5.3's reconciliation, once, for every source.
 *
 * <p>Fifteen lines and one branch, exactly as the design has it: derive an identity from the
 * response, skip what we already hold, insert what we do not, count the result. Nothing here is
 * CSV-specific or Sheets-specific — the source is reached through {@link ComplaintSource}, which
 * is the entire reason the fallback path is <em>the same path</em> (section 5.8). This class was
 * the body of the CSV upload service until the Sheets adapter arrived; it moved rather than being
 * copied, because a second copy is precisely what the port exists to prevent.
 *
 * <p><strong>Not {@code @Transactional}.</strong> The run row and each individual row commit
 * separately (section 5.3), so a transaction spanning the whole run would defeat both: a malformed
 * row would take the run's own record down with it, and a 5000-row upload would hold one
 * transaction open for its entire duration.
 *
 * <p><strong>Nothing a resident wrote is logged.</strong> Every log line here carries counts, row
 * numbers and a run id (section 11.4).
 */
@Service
class IngestionPipeline {

    private static final Logger log = LoggerFactory.getLogger(IngestionPipeline.class);

    private final SourceRowIngestor ingestor;
    private final IngestionRunRecorder runs;

    IngestionPipeline(SourceRowIngestor ingestor, IngestionRunRecorder runs) {
        this.ingestor = ingestor;
        this.runs = runs;
    }

    /**
     * Opens a run, reads the source and reconciles what it returned.
     *
     * <p>A run is the unit a correlation id traces (section 3.7): every audit row this run writes
     * can be pulled out of the log afterwards as one ordered story.
     */
    IngestionSummary run(ComplaintSource source, TriggerType trigger, FetchWindow window) {
        return CorrelationId.runWith(CorrelationId.generate(), () -> execute(source, trigger, window));
    }

    private IngestionSummary execute(ComplaintSource source, TriggerType trigger, FetchWindow window) {
        long runId = runs.open(source.type(), trigger, CurrentUser.idOrNull());

        List<RawRow> rows;
        try {
            rows = source.fetch(window);
        } catch (MissingRequiredColumnException
                | UploadRejectedException
                | SheetsUnavailableException stopped) {
            // Loud, and nothing ingested. A silent partial ingest is far worse than a visible
            // failure, because the manager would have no way to know complaints were dropped —
            // and silence is the failure mode section 5.3 names outright.
            log.warn("Ingestion run {} stopped before any row was read: {}", runId, stopped.getMessage());
            return runs.fail(runId, stopped.getMessage());
        }

        return reconcile(rows, source, runId);
    }

    private IngestionSummary reconcile(List<RawRow> rows, ComplaintSource source, long runId) {
        Instant observedAt = Instant.now();
        Set<String> seenInBatch = new HashSet<>();
        List<String> rowFailures = new ArrayList<>();
        int created = 0;
        int skipped = 0;

        for (RawRow row : rows) {
            String responseKey = responseKeyFor(row, seenInBatch);
            try {
                RowOutcome outcome =
                        ingestor.ingest(row, responseKey, source.type(), source.sourceRef(), runId, observedAt);
                if (outcome == RowOutcome.CREATED) {
                    created++;
                } else {
                    skipped++;
                }
            } catch (MalformedRowException malformed) {
                // Section 5.3: one malformed response cannot abort the run. It is recorded on
                // `ingestion_run.error_detail` and the run continues. The message carries a row
                // number and a field name, never a cell value (section 11.4).
                rowFailures.add(malformed.getMessage());
                skipped++;
            } catch (DataIntegrityViolationException raced) {
                // `uq_source_row` won: something inserted this response between our lookup and our
                // insert. That is the constraint doing its job, not an error.
                log.debug("Ingestion run {} lost a race for row {}; the response is already held", runId, row.rowIndex());
                skipped++;
            }
        }

        log.info(
                "Ingestion run {} read {} row(s): {} created, {} skipped, {} malformed",
                runId,
                rows.size(),
                created,
                skipped,
                rowFailures.size());
        return runs.finish(runId, created, skipped, rowFailures);
    }

    /**
     * Section 5.3's in-batch disambiguator: two submissions in the same second by the same person
     * are two complaints, so the ordinal advances until the key is unused within this batch.
     *
     * <p>It is deterministic in the source's own order, so reading the same rows again reproduces
     * the same keys and still creates nothing.
     */
    private static String responseKeyFor(RawRow row, Set<String> seenInBatch) {
        String timestamp = row.get(SourceField.TIMESTAMP);
        String email = row.get(SourceField.EMAIL);
        int ordinal = 0;
        String key = RowDigest.responseKey(timestamp, email, ordinal);
        while (!seenInBatch.add(key)) {
            key = RowDigest.responseKey(timestamp, email, ++ordinal);
        }
        return key;
    }
}
