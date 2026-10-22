package duty.audit.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import duty.audit.domain.AuditEvent;
import duty.audit.infrastructure.AuditEventRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Records a change to the audit log.
 *
 * <p>The entry is published as an event and written by {@link AuditEventWriter} just before commit,
 * so the log can never disagree with the data.
 */
@Service
public class AuditTrail {

    private final ApplicationEventPublisher events;
    private final AuditEventRepository auditEvents;

    AuditTrail(ApplicationEventPublisher events, AuditEventRepository auditEvents) {
        this.events = events;
        this.auditEvents = auditEvents;
    }

    /**
     * Records a change. Call it inside the transaction that makes the change; outside one, the entry is
     * dropped.
     */
    public void record(AuditEntry entry) {
        events.publishEvent(Objects.requireNonNull(entry, "entry"));
    }

    /** Everything recorded about one thing, oldest first: a complaint's history page. */
    @Transactional(readOnly = true)
    public List<AuditRecord> historyOf(String entityType, long entityId) {
        return auditEvents.findByEntityTypeAndEntityIdOrderByOccurredAtAsc(entityType, entityId).stream()
                .map(
                        event ->
                                new AuditRecord(
                                        event.getOccurredAt(),
                                        event.getActorId(),
                                        event.getOnBehalfOf(),
                                        event.getEventType(),
                                        event.getFromState(),
                                        event.getToState(),
                                        event.getDetail()))
                .toList();
    }

    /** One recorded change, as the history page shows it. {@code actorId} is {@code null} for the system. */
    public record AuditRecord(
            Instant occurredAt,
            Long actorId,
            Long onBehalfOf,
            String eventType,
            String fromState,
            String toState,
            String detail) {}

    /**
     * One change, as handed to {@link AuditTrail} by the module that made it.
     *
     * <p>It lives in {@code application} because other modules build it and may not reach
     * {@code audit.domain}. The subject is named by {@code (entityType, entityId)} because one log covers
     * many tables. {@code onBehalfOf} is set only when the manager acts for an attender.
     */
    public record AuditEntry(
            Instant occurredAt,
            Long actorId,
            String actorRole,
            String entityType,
            Long entityId,
            String eventType,
            String fromState,
            String toState,
            String detail,
            String correlationId,
            Long onBehalfOf) {

        /** Everything but {@code onBehalfOf}: every change except an act-as. */
        public AuditEntry(
                Instant occurredAt,
                Long actorId,
                String actorRole,
                String entityType,
                Long entityId,
                String eventType,
                String fromState,
                String toState,
                String detail,
                String correlationId) {
            this(
                    occurredAt, actorId, actorRole, entityType, entityId, eventType, fromState, toState, detail,
                    correlationId, null);
        }

        /** Fills in the two ambient values so callers never have to think about them. */
        public AuditEntry {
            if (occurredAt == null) {
                occurredAt = Instant.now();
            }
            if (correlationId == null) {
                correlationId = CorrelationId.current();
            }
        }

        /** A state transition of an aggregate — the common case (state machines section 4.1). */
        public static AuditEntry transition(
                String entityType,
                Long entityId,
                String eventType,
                String fromState,
                String toState,
                Long actorId,
                String actorRole) {
            return new AuditEntry(
                    null, actorId, actorRole, entityType, entityId, eventType, fromState, toState, null, null);
        }

        /** A field-level change, e.g. a docket corrected from one number to another (E17). */
        public static AuditEntry change(
                String entityType, Long entityId, String eventType, String detail, Long actorId, String actorRole) {
            return new AuditEntry(
                    null, actorId, actorRole, entityType, entityId, eventType, null, null, detail, null);
        }

        /** Something the system did with no user behind it — a purge, a scheduled escalation. */
        public static AuditEntry systemAction(
                String entityType, Long entityId, String eventType, String detail) {
            return new AuditEntry(null, null, null, entityType, entityId, eventType, null, null, detail, null);
        }
    }

    /**
     * Builds the JSON of {@link AuditEntry#detail()} by serialising, never by concatenating.
     *
     * <p>Why: with concatenation, a value containing a quote could inject extra keys and make the audit
     * log claim something that did not happen. Never put student data in here; audit rows are kept
     * forever.
     */
    public static final class AuditDetail {

        private static final ObjectMapper JSON = new ObjectMapper();

        private final Map<String, Object> values = new LinkedHashMap<>();

        private AuditDetail() {}

        public static AuditDetail of(String key, Object value) {
            return new AuditDetail().and(key, value);
        }

        public AuditDetail and(String key, Object value) {
            values.put(Objects.requireNonNull(key, "key"), value);
            return this;
        }

        /** The detail as a JSON object, ready for {@link AuditEntry#detail()}. */
        public String json() {
            try {
                return JSON.writeValueAsString(values);
            } catch (JsonProcessingException impossible) {
                // Only strings, numbers, booleans and nulls are ever put in; none of them can fail.
                throw new IllegalStateException("audit detail could not be serialised", impossible);
            }
        }

        @Override
        public String toString() {
            return json();
        }
    }

    /**
     * The correlation id for the current thread: one id shared by every audit row of one request or
     * ingestion run.
     *
     * <p>Kept in a {@code ThreadLocal} so it does not have to be passed through every method.
     * {@link #runWith} always restores the previous value, so a reused thread never inherits an old id.
     */
    public static final class CorrelationId {

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

    /**
     * Saves each audit entry inside the transaction that made the change.
     *
     * <p>Why {@code BEFORE_COMMIT}: if the change rolls back, so does the audit row, and if the row cannot
     * be written, the change rolls back too. No {@code @Transactional} here, because it must join the
     * caller's transaction, never start one.
     */
    @Component
    static class AuditEventWriter {

        private final AuditEventRepository auditEvents;

        AuditEventWriter(AuditEventRepository auditEvents) {
            this.auditEvents = auditEvents;
        }

        /** An entry recorded outside a transaction is dropped ({@code fallbackExecution = false}). */
        @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
        void onAuditEntry(AuditEntry entry) {
            auditEvents.saveAndFlush(
                    new AuditEvent(
                            entry.occurredAt(),
                            entry.actorId(),
                            entry.actorRole(),
                            entry.entityType(),
                            entry.entityId(),
                            entry.eventType(),
                            entry.fromState(),
                            entry.toState(),
                            entry.detail(),
                            entry.correlationId(),
                            entry.onBehalfOf()));
        }
    }
}
