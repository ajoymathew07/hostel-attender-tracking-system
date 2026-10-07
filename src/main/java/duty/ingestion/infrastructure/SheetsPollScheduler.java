package duty.ingestion.infrastructure;

import duty.ingestion.application.SheetsIngestionService.IngestionHealth;
import duty.ingestion.application.SheetsIngestionService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The scheduled backstop poll of the response sheet.
 *
 * <p>{@code @SchedulerLock} stops two instances polling at once; the unique constraint on source rows
 * still prevents duplicates if they did. The first poll waits one interval, since startup catch-up has
 * just run.
 */
@Component
public class SheetsPollScheduler {

    private static final Logger log = LoggerFactory.getLogger(SheetsPollScheduler.class);

    private final SheetsIngestionService sheets;
    private final IngestionHealth health;

    SheetsPollScheduler(SheetsIngestionService sheets, IngestionHealth health) {
        this.sheets = sheets;
        this.health = health;
    }

    /**
     * Returns void because ShedLock cannot invent a result when the lock is held. The outcome is on
     * {@code ingestion_run}.
     */
    @Scheduled(
            fixedDelayString = "${ingestion.sheets.poll-interval}",
            initialDelayString = "${ingestion.sheets.poll-interval}")
    @SchedulerLock(name = "sheetsPoll", lockAtMostFor = "5m")
    public void poll() {
        // Back off while the sheet keeps failing: skip this tick instead of calling the API.
        if (health.backingOff()) {
            log.debug(
                    "Skipping the poll: ingestion is backing off after {} consecutive failure(s)",
                    health.consecutiveFailures());
            return;
        }
        sheets.poll();
    }

    /**
     * Runs one ingestion when the app starts, so responses that arrived while it was asleep are picked up
     * without waiting for the first poll.
     *
     * <p>Uses {@link ApplicationReadyEvent} so the database is ready first. It never fails startup: an
     * unconfigured sheet is skipped and an unreachable one is recorded as a failed run.
     */
    @Component
    static class StartupCatchUp {

        private final SheetsIngestionService sheets;

        StartupCatchUp(SheetsIngestionService sheets) {
            this.sheets = sheets;
        }

        @EventListener(ApplicationReadyEvent.class)
        void catchUp() {
            sheets.catchUp();
        }
    }
}
