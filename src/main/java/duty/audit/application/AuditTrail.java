package duty.audit.application;

import java.util.Objects;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Records a change to the audit log.
 *
 * <p>The entry is published as an event and written by {@link AuditEventWriter} just before commit,
 * so the log can never disagree with the data.
 */
@Service
public class AuditTrail {

    private final ApplicationEventPublisher events;

    AuditTrail(ApplicationEventPublisher events) {
        this.events = events;
    }

    /**
     * Records a change. Call it inside the transaction that makes the change; outside one, the entry is
     * dropped.
     */
    public void record(AuditEntry entry) {
        events.publishEvent(Objects.requireNonNull(entry, "entry"));
    }
}
