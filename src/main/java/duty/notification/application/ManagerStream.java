package duty.notification.application;

import duty.audit.application.AuditTrail.AuditEntry;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The manager dashboard's live feed, {@code /api/manager/stream} (section 9.5).
 *
 * <p>Every audited change is a change worth showing, so the feed listens to the audit entries rather
 * than to a list of events of its own. It sends after commit, so it can never describe a change that
 * rolled back. Each message carries only the event name and the id of what changed; the page refetches
 * itself, which is also what it does after a dropped connection (ADR-012).
 */
@Component
public class ManagerStream {

    /** The browser reconnects on its own after this, and the page refetches. */
    private static final long TIMEOUT_MILLIS = Duration.ofMinutes(30).toMillis();

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    @PreAuthorize("hasRole('MANAGER')")
    public SseEmitter open() {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MILLIS);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(emitter::complete);
        emitter.onError(failure -> emitters.remove(emitter));
        emitters.add(emitter);
        return emitter;
    }

    @TransactionalEventListener
    public void onChange(AuditEntry entry) {
        broadcast(
                () ->
                        SseEmitter.event()
                                .data(
                                        Map.of(
                                                "event", entry.eventType(),
                                                "entity", entry.entityType(),
                                                "id", entry.entityId())));
    }

    /** A comment every 20 s, so a free-tier proxy does not close an idle connection. */
    @Scheduled(fixedRate = 20_000)
    public void heartbeat() {
        broadcast(() -> SseEmitter.event().comment("heartbeat"));
    }

    /** A fresh builder per emitter, because building one consumes it. */
    private void broadcast(Supplier<SseEventBuilder> event) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(event.get());
            } catch (IOException | IllegalStateException gone) {
                emitters.remove(emitter);
            }
        }
    }
}
