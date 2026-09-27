package in.ac.iitm.hostel.duty.audit.infrastructure;

import in.ac.iitm.hostel.duty.audit.domain.AuditEvent;
import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/**
 * The audit log (data model section 3.7).
 *
 * <p><strong>There is no delete path, by construction.</strong> Like the ledger, this interface
 * extends the bare {@code Repository} marker rather than {@code JpaRepository}, so no
 * {@code delete} method exists to be called — the append-only rule is enforced by the type, not by
 * a comment asking people not to. Audit rows are retained indefinitely (section 3.8).
 *
 * <p>{@code saveAndFlush} is what the BEFORE_COMMIT writer uses: the insert must hit the database
 * inside the business transaction, so that a rejected audit row takes the change down with it.
 */
public interface AuditEventRepository extends Repository<AuditEvent, Long> {

    AuditEvent saveAndFlush(AuditEvent event);

    Optional<AuditEvent> findById(Long id);

    long count();

    /** Backed by {@code ix_audit_entity}: the history of one aggregate, oldest first. */
    List<AuditEvent> findByEntityTypeAndEntityIdOrderByOccurredAtAsc(String entityType, Long entityId);

    /** Everything one request or ingestion run did, in order. */
    List<AuditEvent> findByCorrelationIdOrderByOccurredAtAsc(String correlationId);
}
