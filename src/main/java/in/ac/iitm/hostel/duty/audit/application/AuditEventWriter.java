package in.ac.iitm.hostel.duty.audit.application;

import in.ac.iitm.hostel.duty.audit.domain.AuditEvent;
import in.ac.iitm.hostel.duty.audit.infrastructure.AuditEventRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Writes the audit row <strong>inside the transaction that made the change</strong> (data model
 * section 3.7).
 *
 * <p>The phase is the whole point. {@code BEFORE_COMMIT} runs while the business transaction is
 * still open and still owns the JDBC connection, so the audit insert joins it:
 *
 * <ul>
 *   <li>if the change rolls back, the audit row is never written — there is no record of something
 *       that did not happen;
 *   <li>if the audit row cannot be written, the change rolls back with it — a change is never
 *       recorded nowhere.
 * </ul>
 *
 * <p>Neither property survives a move to {@code AFTER_COMMIT}, which would write the row in a
 * transaction of its own and let the two diverge. Notifications are the mirror image and
 * deliberately <em>do</em> fire on {@code AFTER_COMMIT}, so a push can never describe a transaction
 * that rolled back and a slow push service can never hold a database transaction open (section 9.5).
 *
 * <p>There is no {@code @Transactional} here on purpose: the listener must <em>join</em> the
 * caller's transaction, never start one. Annotating it would also break architecture section 2.4
 * rule 4 the moment someone chose the wrong propagation.
 */
@Component
class AuditEventWriter {

    private final AuditEventRepository auditEvents;

    AuditEventWriter(AuditEventRepository auditEvents) {
        this.auditEvents = auditEvents;
    }

    /**
     * {@code fallbackExecution} is deliberately left at its default of {@code false}: an entry
     * recorded with no transaction in progress is dropped rather than written standalone.
     */
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
