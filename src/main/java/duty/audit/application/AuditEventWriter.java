package duty.audit.application;

import duty.audit.domain.AuditEvent;
import duty.audit.infrastructure.AuditEventRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Saves each audit entry inside the transaction that made the change.
 *
 * <p>Why {@code BEFORE_COMMIT}: if the change rolls back, so does the audit row, and if the row cannot
 * be written, the change rolls back too. No {@code @Transactional} here, because it must join the
 * caller's transaction, never start one.
 */
@Component
class AuditEventWriter {

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
                        entry.correlationId()));
    }
}
