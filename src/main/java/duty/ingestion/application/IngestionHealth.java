package duty.ingestion.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Tracks failures of unattended ingestion runs: drives the retry backoff and the "ingestion is failing"
 * banner.
 *
 * <p>Only unattended runs count (poll, startup, wake); "Sync Now" shows its own error. Kept in memory
 * on purpose: after a restart nobody knows whether the sheet is reachable until it is tried.
 * {@code synchronized} because the scheduler, wake endpoint and dashboard all use it.
 */
@Component
public class IngestionHealth {

    /**
     * Wait before the next poll after 1, 2, 3 or more consecutive failures. The first is zero because the
     * normal 3-minute poll interval already gives the 3-minute retry; the last repeats.
     */
    private static final List<Duration> RETRY_DELAY =
            List.of(Duration.ZERO, Duration.ofMinutes(6), Duration.ofMinutes(12), Duration.ofMinutes(30));

    /** Consecutive failures before the manager is shown the banner. */
    private static final int DEGRADED_AFTER = 3;

    private int consecutiveFailures;

    private Instant retryAfter = Instant.EPOCH;

    /** Records an unattended run's result. A success resets the count. */
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

    /** Whether the dashboard shows the degraded-ingestion banner. */
    public synchronized boolean degraded() {
        return consecutiveFailures >= DEGRADED_AFTER;
    }

    /** How many unattended runs have failed in a row, for the banner to say so. */
    public synchronized int consecutiveFailures() {
        return consecutiveFailures;
    }
}
