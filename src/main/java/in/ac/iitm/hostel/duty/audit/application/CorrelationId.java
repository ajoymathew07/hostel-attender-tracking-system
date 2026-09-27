package in.ac.iitm.hostel.duty.audit.application;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * The ambient correlation id for the current thread.
 *
 * <p>{@code audit_event.correlation_id} exists so that everything done by one request, or by one
 * ingestion run, can be pulled out of the log as a single ordered story (data model section 3.7).
 * Threading that id explicitly through every method signature between a controller and an audit
 * call would be noise, so it is carried here instead and read by {@link AuditEntry} when the caller
 * does not supply one.
 *
 * <p>The value is always cleared in a {@code finally} block by {@link #runWith}, so a pooled thread
 * never inherits the correlation of whatever it handled previously.
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
