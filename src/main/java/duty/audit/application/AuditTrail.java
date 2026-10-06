package duty.audit.application;

import java.util.Objects;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * The audit module's published service (architecture section 2.4, rule 3).
 *
 * <p>Callers record a change from inside the transaction that makes it. The row is not written
 * immediately: it is published and picked up by a {@code BEFORE_COMMIT} listener, so it lands in
 * the same transaction and the log can never disagree with the data (data model section 3.7).
 *
 * <p>The entry is published as an application event for {@link AuditEventWriter}. The indirection through an event is what buys the {@code BEFORE_COMMIT} phase: Spring binds
 * the listener to the current transaction's synchronization, so the write happens at a point we
 * could not otherwise name from inside a service method.
 */
@Service
public class AuditTrail {

    private final ApplicationEventPublisher events;

    AuditTrail(ApplicationEventPublisher events) {
        this.events = events;
    }

    /**
     * Records a change. Must be called inside the transaction that performs the change — outside
     * one the entry is dropped rather than silently written in a transaction of its own, because a
     * standalone audit row would be a claim about a change that may never have happened.
     */
    public void record(AuditEntry entry) {
        events.publishEvent(Objects.requireNonNull(entry, "entry"));
    }
}
