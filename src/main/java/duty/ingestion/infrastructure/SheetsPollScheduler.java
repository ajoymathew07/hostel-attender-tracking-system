package duty.ingestion.infrastructure;

import duty.ingestion.application.IngestionHealth;
import duty.ingestion.application.SheetsIngestionService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The backstop poll of section 2.5, and the lock that keeps it safe.
 *
 * <p>Polling alone cannot be relied on — a free-tier instance asleep has no scheduler — so this is
 * the backstop rather than the mechanism. The Apps Script wake-up trigger and the manager's "Sync
 * Now" are the other two paths, and all three converge on the same watermark-and-hash delta
 * computation, so a duplicate trigger, a trigger for a row we already hold, and a trigger that
 * never arrives all produce the same result (section 5.3).
 *
 * <p><strong>{@code @SchedulerLock} is the point of this class.</strong> If the platform ever runs
 * two instances they must not both poll (section 2.5, point 4). The lock is a row in the one
 * database, which is already the single source of truth (section 2.6). It is belt and braces over
 * {@code uq_source_row} rather than a replacement for it: the constraint is what makes double
 * ingestion impossible, the lock is what stops us paying for the attempt.
 *
 * <p>The first poll is one interval after start, not at start. A restart loop on the free tier
 * would otherwise hammer the API, {@code StartupCatchUp} has already reconciled once by then, and
 * the manager has "Sync Now" for an immediate read.
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
     * Void by necessity: ShedLock's method proxy can only lock a method whose result it does not
     * have to invent when the lock is already held. The run's outcome is on {@code ingestion_run}
     * and in the log, which is where an unattended job's outcome belongs anyway.
     */
    @Scheduled(
            fixedDelayString = "${ingestion.sheets.poll-interval}",
            initialDelayString = "${ingestion.sheets.poll-interval}")
    @SchedulerLock(name = "sheetsPoll", lockAtMostFor = "5m")
    public void poll() {
        // Section 5.3's failure budget. A sheet that is failing will keep failing for a while —
        // an expired credential, a revoked share, a Google outage — and asking every three
        // minutes spends quota to learn nothing. Skipping ticks is how the backoff is applied:
        // the tick still fires, it just declines to call the API.
        if (health.backingOff()) {
            log.debug(
                    "Skipping the poll: ingestion is backing off after {} consecutive failure(s)",
                    health.consecutiveFailures());
            return;
        }
        sheets.poll();
    }
}
