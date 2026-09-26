package in.ac.iitm.hostel.duty.ingestion.application;

import in.ac.iitm.hostel.duty.audit.application.AuditDetail;
import in.ac.iitm.hostel.duty.audit.application.AuditEntry;
import in.ac.iitm.hostel.duty.audit.application.AuditTrail;
import in.ac.iitm.hostel.duty.identity.application.CurrentUser;
import in.ac.iitm.hostel.duty.ingestion.domain.IngestionRun;
import in.ac.iitm.hostel.duty.ingestion.domain.SourceType;
import in.ac.iitm.hostel.duty.ingestion.domain.TriggerType;
import in.ac.iitm.hostel.duty.ingestion.infrastructure.IngestionRunRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Opens and closes the {@code ingestion_run} row (section 5.3).
 *
 * <p>Each call is its own transaction, and separate from the per-row ones. The run must survive
 * being recorded even when the rows beneath it did not: "the 09:14 upload failed because the
 * description column was renamed" is exactly the information the manager needs, and it is useless
 * if it rolls back with the failure it describes.
 *
 * <p>Separate bean rather than a private method for the same reason as {@link SourceRowIngestor}:
 * self-invocation bypasses the proxy, and {@code REQUIRES_NEW} would quietly not apply.
 */
@Service
class IngestionRunRecorder {

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
     * Closes a run that got through its rows.
     *
     * <p>Malformed rows do <em>not</em> fail the run (section 5.3): they are counted as skipped and
     * listed on {@code error_detail}, and the run is a success because the rows that were fine were
     * ingested. A run that reported failure because one row in 500 had a bad date would train the
     * manager to ignore the status.
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

    /** A run that stopped: a required column could not be resolved, or the file broke a limit. */
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
