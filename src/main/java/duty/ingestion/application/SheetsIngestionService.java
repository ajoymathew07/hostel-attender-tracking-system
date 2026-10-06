package duty.ingestion.application;

import duty.ingestion.domain.ColumnMapping;
import duty.ingestion.domain.FetchWindow;
import duty.ingestion.domain.SourceType;
import duty.ingestion.domain.TriggerType;
import duty.ingestion.infrastructure.GoogleSheetsSource;
import duty.ingestion.infrastructure.SheetReader;
import duty.ingestion.infrastructure.SheetsProperties;
import duty.ingestion.infrastructure.SourceRowRepository;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * Ingestion from the Google Form response sheet.
 *
 * <p>Four entry points (Sync Now, scheduled poll, Apps Script wake, startup catch-up) run the same
 * code and differ only in the trigger they record. The three unattended ones report to
 * {@link IngestionHealth}.
 */
@Service
public class SheetsIngestionService {

    private static final Logger log = LoggerFactory.getLogger(SheetsIngestionService.class);

    private final IngestionPipeline pipeline;
    private final IngestionHealth health;
    private final SourceRowRepository sourceRows;
    private final SheetReader reader;
    private final SheetsProperties properties;
    private final ColumnMapping columnMapping;

    SheetsIngestionService(
            IngestionPipeline pipeline,
            IngestionHealth health,
            SourceRowRepository sourceRows,
            SheetReader reader,
            SheetsProperties properties,
            ColumnMapping columnMapping) {
        this.pipeline = pipeline;
        this.health = health;
        this.sourceRows = sourceRows;
        this.reader = reader;
        this.properties = properties;
        this.columnMapping = columnMapping;
    }

    /**
     * The manager's "Sync Now" button. Manager-only.
     *
     * @return the run's outcome, or empty when no sheet or credential is configured
     */
    @PreAuthorize("hasRole('MANAGER')")
    public Optional<IngestionSummary> syncNow() {
        return ingest(TriggerType.MANUAL);
    }

    /**
     * The scheduled backstop poll. Called only by the scheduler, which holds the cross-instance lock.
     */
    public Optional<IngestionSummary> poll() {
        return unattended(TriggerType.SCHEDULED);
    }

    /**
     * Run when the Apps Script trigger says the sheet changed. A repeated trigger is harmless: rows
     * already held are skipped.
     */
    public Optional<IngestionSummary> wake() {
        return unattended(TriggerType.MANUAL);
    }

    /** Catch-up on startup, so responses that arrived while the app was asleep are not missed. */
    public Optional<IngestionSummary> catchUp() {
        return unattended(TriggerType.STARTUP);
    }

    /** Whether a sheet id and a credential are both configured. */
    public boolean configured() {
        return properties.configured();
    }

    /**
     * A run with nobody watching, so its outcome feeds the banner and backoff. An unconfigured instance
     * records nothing.
     */
    private Optional<IngestionSummary> unattended(TriggerType trigger) {
        Optional<IngestionSummary> outcome = ingest(trigger);
        outcome.ifPresent(health::record);
        return outcome;
    }

    private Optional<IngestionSummary> ingest(TriggerType trigger) {
        if (!properties.configured()) {
            // No sheet id, or no GOOGLE_SA_KEY. That is the normal state on a developer machine
            // and in CI, and it must not be an error: the CSV fallback is the other half of this
            // port and keeps working (section 5.8).
            log.debug("Sheets ingestion is not configured; skipping the {} run", trigger);
            return Optional.empty();
        }

        GoogleSheetsSource source =
                new GoogleSheetsSource(
                        reader, properties.getSpreadsheetId(), properties.getSheetName(), columnMapping);
        return Optional.of(pipeline.run(source, trigger, window()));
    }

    /**
     * Where to start reading: derived from the highest row already held, not stored separately, so it can
     * never disagree with the data.
     */
    private FetchWindow window() {
        Integer lastRowSeen =
                sourceRows.maxRowIndex(SourceType.GOOGLE_SHEETS, properties.getSpreadsheetId());
        return GoogleSheetsSource.windowFrom(lastRowSeen);
    }
}
