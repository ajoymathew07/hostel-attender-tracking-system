package duty.notification.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import duty.identity.application.CurrentUser;
import duty.notification.domain.PushSubscription;
import duty.notification.infrastructure.PushSubscriptionRepository;
import duty.notification.infrastructure.WebPushClient;
import duty.workflow.application.AttenderAlerts.AttenderAlert;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Web Push to attender phones (section 9.6): registering a device, and sending it the alerts the
 * workflow announces.
 *
 * <p>Push is best-effort. A device that answers 404 or 410 has gone and is revoked at once; any other
 * failure is counted, and the third in a row revokes it. There is no retry queue, because the queue
 * screen stays the source of truth.
 */
@Service
@Transactional
public class PushNotifications {

    private static final Logger log = LoggerFactory.getLogger(PushNotifications.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final PushSubscriptionRepository subscriptions;
    private final WebPushClient client;

    PushNotifications(PushSubscriptionRepository subscriptions, WebPushClient client) {
        this.subscriptions = subscriptions;
        this.client = client;
    }

    /** The VAPID public key, or empty when push is not configured on this deployment. */
    public Optional<String> publicKey() {
        return client.enabled() ? Optional.of(client.publicKey()) : Optional.empty();
    }

    /** Registers this browser for the signed-in attender; the same browser again updates its row. */
    @PreAuthorize("hasRole('ATTENDER')")
    public void subscribe(String endpoint, String p256dh, String auth, String deviceLabel) {
        Long me = CurrentUser.idOrNull();
        subscriptions
                .findByEndpoint(endpoint)
                .ifPresentOrElse(
                        existing -> existing.renew(me, p256dh, auth, deviceLabel),
                        () ->
                                subscriptions.save(
                                        new PushSubscription(me, endpoint, p256dh, auth, deviceLabel, Instant.now())));
    }

    @PreAuthorize("hasRole('ATTENDER')")
    public void unsubscribe(String endpoint) {
        subscriptions
                .findByEndpoint(endpoint)
                .filter(subscription -> subscription.getUserId().equals(CurrentUser.idOrNull()))
                .ifPresent(subscription -> subscription.revoke(Instant.now()));
    }

    /** Sends a test alert to the signed-in attender's own devices and returns how many accepted it. */
    @PreAuthorize("hasRole('ATTENDER')")
    public int sendTest() {
        return sendTo(CurrentUser.idOrNull(), message("Alerts are on", "This phone will be told about new work", "test", "/m"));
    }

    /**
     * Pushes an assignment or escalation once it has committed.
     *
     * <p>{@code REQUIRES_NEW} because the transaction that announced it has already finished, and the
     * delivery bookkeeping needs one of its own.
     */
    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onAlert(AttenderAlert alert) {
        String place = alert.room() == null ? alert.publicRef() : "Room " + alert.room();
        String title =
                alert.escalated()
                        ? "⏰ Now urgent: " + place
                        : alert.urgent() ? "🔴 Urgent: " + place : "New task: " + place;
        sendTo(
                alert.attenderId(),
                message(title, alert.publicRef() + " · " + alert.domain(), "task-" + alert.taskId(), "/m/tasks/" + alert.taskId()));
    }

    private int sendTo(Long userId, byte[] payload) {
        if (!client.enabled()) {
            return 0;
        }
        Instant now = Instant.now();
        int delivered = 0;
        for (PushSubscription device : subscriptions.findByUserIdAndRevokedAtIsNull(userId)) {
            int status;
            try {
                status = client.send(device.getEndpoint(), device.getP256dhKey(), device.getAuthSecret(), payload);
            } catch (Exception unreachable) {
                log.warn("Push to device {} failed: {}", device.getId(), unreachable.toString());
                status = -1;
            }
            if (status >= 200 && status < 300) {
                device.delivered(now);
                delivered++;
            } else if (status == 404 || status == 410) {
                device.revoke(now);
            } else {
                device.failed(now);
            }
        }
        return delivered;
    }

    /** The payload the service worker shows; {@code tag} makes an escalation replace the first alert. */
    private static byte[] message(String title, String body, String tag, String url) {
        try {
            return JSON.writeValueAsBytes(Map.of("title", title, "body", body, "tag", tag, "url", url));
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("push payload could not be serialised", impossible);
        }
    }
}
