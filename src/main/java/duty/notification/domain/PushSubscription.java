package duty.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;

/**
 * One browser install that has agreed to receive push for one user (section 3.7).
 *
 * <p>Keyed on the endpoint ({@code uq_push_endpoint}), so the same browser subscribing again updates
 * this row instead of adding a second one.
 */
@Entity
@Table(name = "push_subscription")
public class PushSubscription {

    /** Consecutive failures after which a device is treated as gone (section 9.6). */
    public static final int MAX_FAILURES = 3;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "endpoint", nullable = false, length = 512)
    private String endpoint;

    @Column(name = "p256dh_key", nullable = false, length = 128)
    private String p256dhKey;

    @Column(name = "auth_secret", nullable = false, length = 64)
    private String authSecret;

    @Column(name = "device_label", length = 64)
    private String deviceLabel;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_ok_at")
    private Instant lastOkAt;

    @Column(name = "fail_count", nullable = false)
    private int failCount;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    /** For JPA only. */
    protected PushSubscription() {}

    public PushSubscription(
            Long userId, String endpoint, String p256dhKey, String authSecret, String deviceLabel, Instant now) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.createdAt = Objects.requireNonNull(now, "now");
        renew(userId, p256dhKey, authSecret, deviceLabel);
    }

    /** The browser subscribed again, possibly with new keys or after being revoked. */
    public void renew(Long userId, String p256dhKey, String authSecret, String deviceLabel) {
        this.userId = Objects.requireNonNull(userId, "userId");
        this.p256dhKey = Objects.requireNonNull(p256dhKey, "p256dhKey");
        this.authSecret = Objects.requireNonNull(authSecret, "authSecret");
        this.deviceLabel = deviceLabel;
        this.failCount = 0;
        this.revokedAt = null;
    }

    public void delivered(Instant now) {
        this.lastOkAt = now;
        this.failCount = 0;
    }

    /** Counts a failure; the third in a row revokes the device. */
    public void failed(Instant now) {
        failCount++;
        if (failCount >= MAX_FAILURES) {
            revoke(now);
        }
    }

    public void revoke(Instant now) {
        if (revokedAt == null) {
            revokedAt = now;
        }
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public String getP256dhKey() {
        return p256dhKey;
    }

    public String getAuthSecret() {
        return authSecret;
    }

    public int getFailCount() {
        return failCount;
    }

    public Instant getLastOkAt() {
        return lastOkAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }
}
