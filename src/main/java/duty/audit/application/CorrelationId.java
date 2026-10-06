package duty.audit.application;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * The correlation id for the current thread: one id shared by every audit row of one request or
 * ingestion run.
 *
 * <p>Kept in a {@code ThreadLocal} so it does not have to be passed through every method.
 * {@link #runWith} always restores the previous value, so a reused thread never inherits an old id.
 */
public final class CorrelationId {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private CorrelationId() {}

    /** A fresh correlation id for a new request or run. */
    public static String generate() {
        return UUID.randomUUID().toString();
    }

    /** The correlation in force, or {@code null} if nothing set one. */
    public static String current() {
        return CURRENT.get();
    }

    /** Runs {@code work} under the given correlation, restoring the previous value afterwards. */
    public static <T> T runWith(String correlationId, Supplier<T> work) {
        String previous = CURRENT.get();
        CURRENT.set(correlationId);
        try {
            return work.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /** Runs {@code work} under the given correlation. */
    public static void runWith(String correlationId, Runnable work) {
        runWith(
                correlationId,
                () -> {
                    work.run();
                    return null;
                });
    }

    /** Clears the correlation for this thread. */
    public static void clear() {
        CURRENT.remove();
    }
}
