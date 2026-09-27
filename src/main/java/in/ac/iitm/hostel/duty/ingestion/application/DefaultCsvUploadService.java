package in.ac.iitm.hostel.duty.ingestion.application;

import in.ac.iitm.hostel.duty.audit.application.CorrelationId;
import in.ac.iitm.hostel.duty.identity.application.CurrentUser;
import in.ac.iitm.hostel.duty.ingestion.domain.ColumnMapping;
import in.ac.iitm.hostel.duty.ingestion.domain.ComplaintSource;
import in.ac.iitm.hostel.duty.ingestion.domain.FetchWindow;
import in.ac.iitm.hostel.duty.ingestion.domain.MalformedRowException;
import in.ac.iitm.hostel.duty.ingestion.domain.MissingRequiredColumnException;
import in.ac.iitm.hostel.duty.ingestion.domain.RawRow;
import in.ac.iitm.hostel.duty.ingestion.domain.RowDigest;
import in.ac.iitm.hostel.duty.ingestion.domain.SourceField;
import in.ac.iitm.hostel.duty.ingestion.domain.SourceType;
import in.ac.iitm.hostel.duty.ingestion.domain.TriggerType;
import in.ac.iitm.hostel.duty.ingestion.domain.UploadLimits;
import in.ac.iitm.hostel.duty.ingestion.domain.UploadRejectedException;
import in.ac.iitm.hostel.duty.ingestion.infrastructure.CsvComplaintSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * The CSV fallback pipeline (sections 5.3, 5.8).
 *
 * <p>Fifteen lines of reconciliation and one branch, exactly as section 5.3 has it: derive an
 * identity from the response, skip what we already hold, insert what we do not, count the result.
 * Nothing here is CSV-specific — the source is reached through {@link ComplaintSource}, so PR 8's
 * Sheets adapter runs this same loop rather than growing a second copy of it.
 *
 * <p><strong>Not {@code @Transactional}.</strong> The run row and each individual row commit
 * separately (section 5.3), so a transaction spanning the whole upload would defeat both: a
 * malformed row would take the run's own record down with it, and a 5000-row upload would hold one
 * transaction open for its entire duration.
 *
 * <p><strong>Nothing a resident wrote is logged.</strong> Every log line here carries counts, row
 * numbers and a run id. The student's name, roll number and phone number are stored and shown to
 * the manager, and never written to a log (section 11.4).
 */
@Service
class DefaultCsvUploadService implements CsvUploadService {

    private static final Logger log = LoggerFactory.getLogger(DefaultCsvUploadService.class);

    private final SourceRowIngestor ingestor;
    private final IngestionRunRecorder runs;
    private final ColumnMapping columnMapping;
    private final UploadLimits limits;

    DefaultCsvUploadService(
            SourceRowIngestor ingestor,
            IngestionRunRecorder runs,
            ColumnMapping columnMapping,
            UploadLimits limits) {
        this.ingestor = ingestor;
        this.runs = runs;
        this.columnMapping = columnMapping;
        this.limits = limits;
    }

    /**
     * {@code @PreAuthorize} on the service is the control (section 11.3). The upload form is also
     * hidden from an attender, but hiding a form is decoration; this is the check, and it holds for
     * any future caller that reaches the service by another route.
     */
    @Override
    @PreAuthorize("hasRole('MANAGER')")
    public IngestionSummary upload(UploadedFile file) {
        // Section 11.6 — media type and size are decided before a single byte is parsed.
        limits.validate(file.filename(), file.contentType(), file.size());

        // A run is the unit a correlation id traces (section 3.7): every audit row this upload
        // writes can be pulled out of the log afterwards as one ordered story.
        return CorrelationId.runWith(CorrelationId.generate(), () -> run(file));
    }

    private IngestionSummary run(UploadedFile file) {
        long runId = runs.open(SourceType.CSV_UPLOAD, TriggerType.MANUAL, CurrentUser.idOrNull());
        ComplaintSource source = new CsvComplaintSource(file.content(), columnMapping, limits);

        List<RawRow> rows;
        try {
            rows = source.fetch(FetchWindow.all());
        } catch (MissingRequiredColumnException | UploadRejectedException stopped) {
            // Loud, and nothing ingested. A silent partial ingest is far worse than a visible
            // failure, because the manager would have no way to know complaints were dropped.
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
     * <p>It is deterministic in the file's own order, so re-uploading the same file reproduces the
     * same keys and still creates nothing.
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
