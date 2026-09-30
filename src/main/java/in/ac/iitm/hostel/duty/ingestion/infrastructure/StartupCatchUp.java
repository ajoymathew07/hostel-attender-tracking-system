package in.ac.iitm.hostel.duty.ingestion.infrastructure;

import in.ac.iitm.hostel.duty.ingestion.application.SheetsIngestionService;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The third mitigation of section 2.5: one reconciliation on boot, so a wake after sleep loses
 * nothing.
 *
 * <p>A free-tier instance that slept through six hours of responses has a scheduler that never
 * ran. The first poll is deliberately one interval <em>after</em> start (see {@link
 * SheetsPollScheduler}), so without this the newly woken instance would sit on a stale sheet for
 * three more minutes. This closes that window.
 *
 * <p><strong>There is no catch-up algorithm.</strong> It calls the same reconciliation the poll
 * calls, and it is safe for the reason section 5.3 gives: delta detection is watermark-based
 * rather than time-based, so a six-hour gap takes the identical path to a six-minute one with a
 * wider window. The only thing that makes this a distinct class is <em>when</em> it fires.
 *
 * <p>{@link ApplicationReadyEvent} rather than a constructor or {@code @PostConstruct}: the
 * database, Flyway and the transaction manager must all be up before a row is written, and
 * "ready" is the only event that promises it. An unconfigured instance and an unreachable sheet
 * both return normally — the first skips before opening a run, the second records a failed one —
 * so ingestion cannot take startup down with it.
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
