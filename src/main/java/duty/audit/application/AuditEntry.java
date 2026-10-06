package duty.audit.application;

import java.time.Instant;

/**
 * One change, as handed to {@link AuditTrail} by the module that made it.
 *
 * <p>It lives in {@code application} because other modules build it and may not reach
 * {@code audit.domain}. The subject is named by {@code (entityType, entityId)} because one log covers
 * many tables.
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
        String correlationId) {

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
