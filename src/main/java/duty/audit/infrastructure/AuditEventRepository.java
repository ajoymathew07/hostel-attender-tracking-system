package duty.audit.infrastructure;

import duty.audit.domain.AuditEvent;
import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/** The audit log. Extends the bare {@code Repository} so no delete method exists. */
public interface AuditEventRepository extends Repository<AuditEvent, Long> {

    AuditEvent saveAndFlush(AuditEvent event);

    Optional<AuditEvent> findById(Long id);

    long count();

    /** Backed by {@code ix_audit_entity}: the history of one aggregate, oldest first. */
    List<AuditEvent> findByEntityTypeAndEntityIdOrderByOccurredAtAsc(String entityType, Long entityId);

    /** Everything one request or ingestion run did, in order. */
    List<AuditEvent> findByCorrelationIdOrderByOccurredAtAsc(String correlationId);
}
