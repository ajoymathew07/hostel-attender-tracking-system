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
 * The ingestion loop shared by every source: key each row, skip rows already held, ingest the rest,
 * count the result.
 *
 * <p>Not {@code @Transactional}: the run record and each row commit separately, so one bad row cannot
 * undo the run's record, and a big upload does not hold one long transaction. Logs carry counts and
 * row numbers only, never student data.
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

    /** Opens a run, reads the source and ingests the rows, all under one correlation id. */
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
     * The response key for a row. Two submissions by the same person in the same second get different
     * ordinals, so both become complaints. Deterministic, so re-reading the same rows gives the same keys.
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
