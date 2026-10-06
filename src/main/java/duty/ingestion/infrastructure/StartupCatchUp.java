package duty.ingestion.infrastructure;

import duty.ingestion.application.SheetsIngestionService;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Runs one ingestion when the app starts, so responses that arrived while it was asleep are picked up
 * without waiting for the first poll.
 *
 * <p>Uses {@link ApplicationReadyEvent} so the database is ready first. It never fails startup: an
 * unconfigured sheet is skipped and an unreachable one is recorded as a failed run.
 */
@Component
class StartupCatchUp {

    private final SheetsIngestionService sheets;

    StartupCatchUp(SheetsIngestionService sheets) {
        this.sheets = sheets;
    }

    @EventListener(ApplicationReadyEvent.class)
    void catchUp() {
        sheets.catchUp();
    }
}
