package duty.notification.infrastructure;

import java.security.GeneralSecurityException;
import java.security.Security;
import nl.martijndwars.webpush.Encoding;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Sends one encrypted message to one browser's push service, signed with our VAPID key (ADR-011).
 *
 * <p>The only class that touches the Web Push library. Without both VAPID keys configured push is
 * off: {@link #enabled()} is false and nothing is sent, as on a developer machine or in CI.
 */
@Component
public class WebPushClient {

    private final String publicKey;
    private final PushService pushService;

    WebPushClient(
            @Value("${notification.vapid.public-key:}") String publicKey,
            @Value("${notification.vapid.private-key:}") String privateKey,
            @Value("${notification.vapid.subject:}") String subject)
            throws GeneralSecurityException {
        this.publicKey = publicKey;
        if (publicKey.isBlank() || privateKey.isBlank()) {
            this.pushService = null;
            return;
        }
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        this.pushService = new PushService(publicKey, privateKey, subject);
    }

    public boolean enabled() {
        return pushService != null;
    }

    /** The VAPID public key a browser needs to subscribe. */
    public String publicKey() {
        return publicKey;
    }

    /**
     * Sends the payload and returns the push service's HTTP status.
     *
     * @throws Exception the push service could not be reached, or the keys were unusable
     */
    public int send(String endpoint, String p256dh, String auth, byte[] payload) throws Exception {
        Notification notification = new Notification(endpoint, p256dh, auth, payload);
        return pushService.send(notification, Encoding.AES128GCM).getStatusLine().getStatusCode();
    }
}
