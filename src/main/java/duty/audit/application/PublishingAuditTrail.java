package duty.audit.application;

import java.util.Objects;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Publishes an {@link AuditEntry} as an application event, for {@link AuditEventWriter} to turn
 * into a row before the transaction commits.
 *
 * <p>The indirection through an event is what buys the {@code BEFORE_COMMIT} phase: Spring binds
 * the listener to the current transaction's synchronization, so the write happens at a point we
 * could not otherwise name from inside a service method.
 */
@Service
class PublishingAuditTrail implements AuditTrail {

    private final ApplicationEventPublisher events;

    PublishingAuditTrail(ApplicationEventPublisher events) {
        this.events = events;
    }

    @Override
    public void record(AuditEntry entry) {
        events.publishEvent(Objects.requireNonNull(entry, "entry"));
    }
}
