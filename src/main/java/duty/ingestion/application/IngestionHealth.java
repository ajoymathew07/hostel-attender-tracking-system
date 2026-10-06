package duty.ingestion.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * How well unattended ingestion is going — the backoff clock and the degraded flag of section 5.3.
 *
 * <p>Two things come out of one counter. The Sheets API must not be hammered while it is failing,
 * and the manager must be <em>told</em>: "silence is the failure mode to avoid — the manager must
 * find out from the UI, not by noticing complaints stopped arriving" (section 5.3).
 *
 * <p><strong>Only unattended runs are counted.</strong> The scheduled poll, the startup catch-up
 * and the Apps Script wake-up have nobody watching them, so they are the runs a banner has to
 * speak for. "Sync Now" already puts its own failure on the manager's screen as a sentence, and
 * backing it off would disable the one escape hatch section 2.5 gives them.
 *
 * <p><strong>In memory, per instance, deliberately.</strong> This is a statement about the last
 * few minutes of one process, not a fact about the hostel: a restart clearing it is correct,
 * because a restarted instance has no idea whether the sheet is reachable until it tries. That
 * also means there is nothing to migrate and nothing to reconcile.
 *
 * <p>Every method is {@code synchronized} — the scheduler thread, the wake-up endpoint's executor
 * and the request thread rendering the dashboard all touch this.
 */
@Component
public class IngestionHealth {

    /**
     * Section 5.3's ladder, 3 → 6 → 12 → 30 minutes, indexed by consecutive failure count.
     *
     * <p>The first rung is zero rather than three because three minutes <em>is</em> the poll
     * interval: the scheduler already waits that long, so "retry in 3 minutes" after one failure
     * asks for no delay this class has to add. The rungs worth implementing are the ones longer
     * than the normal cadence, and the last repeats, which is what "capped" means.
     */
    private static final List<Duration> RETRY_DELAY =
            List.of(Duration.ZERO, Duration.ofMinutes(6), Duration.ofMinutes(12), Duration.ofMinutes(30));

    /** Section 5.3: three consecutive failures is when the manager is told. */
    private static final int DEGRADED_AFTER = 3;

    private int consecutiveFailures;

    private Instant retryAfter = Instant.EPOCH;

    /** Records what an unattended run did. A success clears everything; there is no decay. */
    public synchronized void record(IngestionSummary summary) {
        if (!summary.failed()) {
            consecutiveFailures = 0;
            retryAfter = Instant.EPOCH;
            return;
        }
        consecutiveFailures++;
        retryAfter = Instant.now().plus(RETRY_DELAY.get(Math.min(consecutiveFailures, RETRY_DELAY.size()) - 1));
    }

    /** Whether the scheduled poll should skip this tick rather than call the API again. */
    public synchronized boolean backingOff() {
        return Instant.now().isBefore(retryAfter);
    }

    /** Whether the dashboard shows the degraded-ingestion banner (section 5.3). */
    public synchronized boolean degraded() {
        return consecutiveFailures >= DEGRADED_AFTER;
    }

    /** How many unattended runs have failed in a row, for the banner to say so. */
    public synchronized int consecutiveFailures() {
        return consecutiveFailures;
    }
}
