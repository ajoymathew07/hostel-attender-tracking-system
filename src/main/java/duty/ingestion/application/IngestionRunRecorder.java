package duty.ingestion.application;

import duty.audit.application.AuditDetail;
import duty.audit.application.AuditEntry;
import duty.audit.application.AuditTrail;
import duty.identity.application.CurrentUser;
import duty.ingestion.domain.IngestionRun;
import duty.ingestion.domain.SourceType;
import duty.ingestion.domain.TriggerType;
import duty.ingestion.infrastructure.IngestionRunRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Opens and closes the {@code ingestion_run} row, each in its own transaction.
 *
 * <p>Why: the run record must survive even when its rows fail, because the failure is what the
 * manager needs to see. A separate bean because {@code REQUIRES_NEW} is ignored on self-invocation.
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
