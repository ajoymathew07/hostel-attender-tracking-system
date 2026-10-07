package duty.ingestion.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import duty.audit.application.AuditTrail.AuditDetail;
import duty.audit.application.AuditTrail.AuditEntry;
import duty.audit.application.AuditTrail.CorrelationId;
import duty.audit.application.AuditTrail;
import duty.identity.application.CurrentUser;
import duty.ingestion.domain.ColumnMapping.MissingRequiredColumnException;
import duty.ingestion.domain.ColumnMapping.SourceField;
import duty.ingestion.domain.ComplaintSource.FetchWindow;
import duty.ingestion.domain.ComplaintSource.RawRow;
import duty.ingestion.domain.ComplaintSource;
import duty.ingestion.domain.IngestionRun.IngestionStatus;
import duty.ingestion.domain.IngestionRun.TriggerType;
import duty.ingestion.domain.IngestionRun;
import duty.ingestion.domain.RowNormalizer.MalformedRowException;
import duty.ingestion.domain.RowNormalizer.NormalizedRow;
import duty.ingestion.domain.RowNormalizer.RowDigest;
import duty.ingestion.domain.RowNormalizer;
import duty.ingestion.domain.SourceRow.SourceType;
import duty.ingestion.domain.SourceRow;
import duty.ingestion.domain.UploadLimits.UploadRejectedException;
import duty.ingestion.infrastructure.GoogleSheetsSource.SheetsUnavailableException;
import duty.ingestion.infrastructure.IngestionRunRepository;
import duty.ingestion.infrastructure.SourceRowRepository;
import duty.workflow.application.ComplaintIntake.IngestedComplaint;
import duty.workflow.application.ComplaintIntake.NewComplaint;
import duty.workflow.application.ComplaintIntake;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The ingestion loop shared by every source: key each row, skip rows already held, ingest the rest,
 * count the result.
 *
 * <p>Not {@code @Transactional}: the run record and each row commit separately, so one bad row cannot
 * undo the run's record, and a big upload does not hold one long transaction. Logs carry counts and
 * row numbers only, never student data.
 */
@Service
public class IngestionPipeline {

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

    /** What happened to one source row. */
    public enum RowOutcome {

        /** New response: a source row and a complaint were created. */
        CREATED,

        /** Already held (or an edited response): skipped. */
        SKIPPED
    }

    /**
     * Opens and closes the {@code ingestion_run} row, each in its own transaction.
     *
     * <p>Why: the run record must survive even when its rows fail, because the failure is what the
     * manager needs to see. A separate bean because {@code REQUIRES_NEW} is ignored on self-invocation.
     */
    @Service
    static class IngestionRunRecorder {

        private static final String ENTITY_TYPE = "ingestion_run";

        /** {@code ingestion_run.error_detail} is VARCHAR(2000). */
        private static final int MAX_ERROR_DETAIL = 2000;

        private final IngestionRunRepository runs;
        private final AuditTrail audit;

        IngestionRunRecorder(IngestionRunRepository runs, AuditTrail audit) {
            this.runs = runs;
            this.audit = audit;
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public long open(SourceType sourceType, TriggerType triggerType, Long actorId) {
            IngestionRun run =
                    runs.saveAndFlush(new IngestionRun(sourceType, triggerType, Instant.now(), actorId));
            audit.record(
                    AuditEntry.change(
                            ENTITY_TYPE,
                            run.getId(),
                            "INGESTION_RUN_STARTED",
                            AuditDetail.of("sourceType", sourceType.name()).and("triggerType", triggerType.name()).json(),
                            CurrentUser.idOrNull(),
                            CurrentUser.roleOrNull()));
            return run.getId();
        }

        /**
         * Closes a run that got through its rows. Malformed rows are listed but do not fail the run, since the
         * good rows were ingested.
         */
        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public IngestionSummary finish(long runId, int created, int skipped, List<String> rowFailures) {
            IngestionRun run = runs.findById(runId).orElseThrow();
            for (int i = 0; i < created; i++) {
                run.countRow(true);
            }
            for (int i = 0; i < skipped; i++) {
                run.countRow(false);
            }
            String detail = rowFailures.isEmpty() ? null : summarise(rowFailures);
            run.recordRowFailures(detail);
            run.succeed(Instant.now());
            runs.saveAndFlush(run);

            audit.record(
                    AuditEntry.change(
                            ENTITY_TYPE,
                            runId,
                            "INGESTION_RUN_COMPLETED",
                            AuditDetail.of("rowsCreated", created)
                                    .and("rowsSkipped", skipped)
                                    .and("rowsFailed", rowFailures.size())
                                    .json(),
                            CurrentUser.idOrNull(),
                            CurrentUser.roleOrNull()));

            return new IngestionSummary(
                    runId, run.getRowsSeen(), run.getRowsCreated(), run.getRowsSkipped(), run.getStatus(), detail);
        }

        /** Marks a run as failed: a required column was missing or the file broke a limit. */
        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public IngestionSummary fail(long runId, String reason) {
            IngestionRun run = runs.findById(runId).orElseThrow();
            run.fail(Instant.now(), truncate(reason));
            runs.saveAndFlush(run);

            audit.record(
                    AuditEntry.change(
                            ENTITY_TYPE,
                            runId,
                            "INGESTION_RUN_FAILED",
                            AuditDetail.of("reason", truncate(reason)).json(),
                            CurrentUser.idOrNull(),
                            CurrentUser.roleOrNull()));

            return new IngestionSummary(
                    runId, run.getRowsSeen(), run.getRowsCreated(), run.getRowsSkipped(), run.getStatus(), run.getErrorDetail());
        }

        private static String summarise(List<String> rowFailures) {
            return truncate(rowFailures.size() + " row(s) could not be read: " + String.join("; ", rowFailures));
        }

        private static String truncate(String detail) {
            if (detail == null || detail.length() <= MAX_ERROR_DETAIL) {
                return detail;
            }
            return detail.substring(0, MAX_ERROR_DETAIL - 3) + "...";
        }
    }

    /**
     * Ingests one source row: the only place ingestion creates a complaint.
     *
     * <p>Each row runs in its own transaction ({@code REQUIRES_NEW}) so one bad row cannot abort the run.
     * A separate bean because that annotation is ignored on self-invocation.
     */
    @Service
    static class SourceRowIngestor {

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

    /**
     * Serialises a raw row to JSON for {@code source_row.raw_payload}.
     *
     * <p>Built with Jackson so a quote in a cell cannot break the JSON. The payload holds student details,
     * so it is purged after 90 days and never logged.
     */
    @Component
    static class RawPayloads {

        private final ObjectMapper json = new ObjectMapper();

        public String toJson(RawRow row) {
            Map<String, String> payload = new LinkedHashMap<>();
            List<String> headers = row.headers();
            List<String> cells = row.cells();
            for (int index = 0; index < headers.size(); index++) {
                payload.put(headers.get(index), index < cells.size() ? cells.get(index) : "");
            }
            try {
                return json.writeValueAsString(payload);
            } catch (JsonProcessingException impossible) {
                throw new IllegalStateException("row " + row.rowIndex() + " could not be serialised", impossible);
            }
        }
    }
}
