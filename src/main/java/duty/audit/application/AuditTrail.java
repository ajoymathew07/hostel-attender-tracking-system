package duty.audit.application;

/**
 * The audit module's published service interface (architecture section 2.4, rule 3).
 *
 * <p>Callers record a change from inside the transaction that makes it. The row is not written
 * immediately: it is published and picked up by a {@code BEFORE_COMMIT} listener, so it lands in
 * the same transaction and the log can never disagree with the data (data model section 3.7).
 */
public interface AuditTrail {

    /**
     * Records a change. Must be called inside the transaction that performs the change — outside
     * one the entry is dropped rather than silently written in a transaction of its own, because a
     * standalone audit row would be a claim about a change that may never have happened.
     */
    void record(AuditEntry entry);
}
