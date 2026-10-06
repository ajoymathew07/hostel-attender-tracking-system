package duty.audit.application;

import java.time.Instant;

/**
 * What one change looked like, handed to {@link AuditTrail} by the module that made it.
 *
 * <p>This lives in {@code application} rather than {@code domain} because it is the audit module's
 * <em>published</em> surface: every other module constructs one of these, and no other module may
 * reach {@code audit.domain} (architecture section 2.4, rule 3). {@code AuditEvent} — the entity —
 * stays internal.
 *
 * <p>An entry names its subject by {@code (entityType, entityId)} rather than by a typed reference,
 * for the same reason the column pair is not a foreign key: one log records changes to many
 * different aggregates.
 *
 * @param occurredAt when the change happened; defaulted to now if not supplied
 * @param actorId the acting user, or {@code null} for the system
 * @param actorRole the acting user's role, for readability without a join
 * @param entityType the aggregate's table name, e.g. {@code complaint}
 * @param entityId the aggregate's id
 * @param eventType what happened, e.g. {@code DOCKET_RECORDED}
 * @param fromState previous state for a transition, otherwise {@code null}
 * @param toState new state for a transition, otherwise {@code null}
 * @param detail JSON holding before/after values, e.g. for a corrected docket
 * @param correlationId defaulted from {@link CorrelationId} if not supplied
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
