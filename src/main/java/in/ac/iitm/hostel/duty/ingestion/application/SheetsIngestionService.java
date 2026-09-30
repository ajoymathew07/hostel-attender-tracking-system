package in.ac.iitm.hostel.duty.ingestion.application;

import in.ac.iitm.hostel.duty.ingestion.domain.ColumnMapping;
import in.ac.iitm.hostel.duty.ingestion.domain.FetchWindow;
import in.ac.iitm.hostel.duty.ingestion.domain.SourceType;
import in.ac.iitm.hostel.duty.ingestion.domain.TriggerType;
import in.ac.iitm.hostel.duty.ingestion.infrastructure.GoogleSheetsSource;
import in.ac.iitm.hostel.duty.ingestion.infrastructure.SheetReader;
import in.ac.iitm.hostel.duty.ingestion.infrastructure.SheetsProperties;
import in.ac.iitm.hostel.duty.ingestion.infrastructure.SourceRowRepository;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * Ingestion from the Google Form response sheet — the module's published surface for it (sections
 * 2.5, 5.3).
 *
 * <p>Four entry points, one body. "Sync Now", the scheduled poll, the Apps Script wake-up and the
 * startup catch-up differ only in the {@code trigger_type} they record, because the delta
 * algorithm is watermark-and-hash based rather than time based: a catch-up after a six-hour sleep
 * and a poll three minutes after the last one are the same computation over a different number of
 * rows (section 5.3). That is exactly why section 2.5 can afford three wake-up mitigations — none
 * of them needs a mode of its own.
 *
 * <p>Three of the four are <em>unattended</em>: nobody is watching the result, so their outcome
 * goes to {@link IngestionHealth}, which is what backs the poll off and raises the manager's
 * degraded-ingestion banner. "Sync Now" is the exception — the manager is looking at the screen
 * that shows its failure.
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
     * The manager's "Sync Now" button, and the manual wake-and-sync path (section 2.5, point 2).
     *
     * <p>Manager-only, enforced here rather than only on the button (section 11.3).
     *
     * @return the run's outcome, or empty when no sheet or credential is configured
     */
    @PreAuthorize("hasRole('MANAGER')")
    public Optional<IngestionSummary> syncNow() {
        return ingest(TriggerType.MANUAL);
    }

    /**
     * The three-minute backstop poll (section 2.5, point 1).
     *
     * <p>No authorization check: there is no user. It is called only by the scheduler, which holds
     * the lock that stops two instances double-ingesting.
     */
    public Optional<IngestionSummary> poll() {
        return unattended(TriggerType.SCHEDULED);
    }

    /**
     * The Apps Script wake-up trigger (section 2.5, point 1).
     *
     * <p>The trigger carries no row data — it only signals that the sheet changed — so this is the
     * ordinary delta computation and a repeated or duplicate trigger is harmless: {@code
     * response_key} collides and nothing is created twice.
     *
     * <p>Recorded as {@code MANUAL}, which the data model already defines as "the manager's Sync
     * Now button, or the Apps Script trigger waking the instance" (section 3.5). No authorization
     * check: the caller is Google, not a user, and the control is the shared secret the endpoint
     * checks before it gets here (section 11.6).
     */
    public Optional<IngestionSummary> wake() {
        return unattended(TriggerType.MANUAL);
    }

    /**
     * Catch-up on boot, so a wake after sleep loses nothing (section 2.5, point 3).
     *
     * <p>There is no catch-up <em>mode</em> — this is the same reconciliation every other entry
     * point runs. It is safe precisely because the watermark has not moved while the instance was
     * asleep, so a six-hour gap simply produces a wider window than a six-minute one.
     */
    public Optional<IngestionSummary> catchUp() {
        return unattended(TriggerType.STARTUP);
    }

    /** Whether there is a sheet to read and a credential to read it with (section 11.5). */
    public boolean configured() {
        return properties.configured();
    }

    /**
     * A run with nobody watching it, so its outcome is the one the banner and the backoff read.
     *
     * <p>An unconfigured instance records nothing: skipping because there is no sheet is the
     * normal state on a developer machine, not a failure of the sheet (section 11.5).
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
     * Section 5.3's watermark, read from what we already hold rather than stored separately.
     *
     * <p>{@code source_row.row_index} is the highest sheet row ingestion has ever seen for this
     * sheet, so a second watermark column would be a second copy of a fact the table already
     * states — and one that could disagree with it after a failed run.
     */
    private FetchWindow window() {
        Integer lastRowSeen =
                sourceRows.maxRowIndex(SourceType.GOOGLE_SHEETS, properties.getSpreadsheetId());
        return GoogleSheetsSource.windowFrom(lastRowSeen);
    }
}
