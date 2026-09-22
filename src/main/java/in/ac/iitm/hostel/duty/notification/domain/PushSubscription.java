package in.ac.iitm.hostel.duty.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;

/**
 * One Web Push endpoint — a (attender, browser install) pair (data model section 3.7).
 *
 * <p>{@code uq_push_endpoint} is what makes a re-subscription by the same browser an update rather
 * than a second row (test plan E27). A push service answering {@code 410 Gone}, or three
 * consecutive failures, revokes the row and no further attempts are made (E28).
 */
@Entity
@Table(name = "push_subscription")
public class PushSubscription {

    /** Three consecutive failures retire an endpoint (data model section 3.8). */
    public static final int FAILURE_LIMIT = 3;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Push service URL issued by the browser. Treated as a device identifier (section 11.4). */
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
            Long userId,
            String endpoint,
            String p256dhKey,
            String authSecret,
            String deviceLabel,
            Instant createdAt) {
        this.userId = Objects.requireNonNull(userId, "userId");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.p256dhKey = Objects.requireNonNull(p256dhKey, "p256dhKey");
        this.authSecret = Objects.requireNonNull(authSecret, "authSecret");
        this.deviceLabel = deviceLabel;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.failCount = 0;
    }

    /** The same browser re-subscribing rotates its keys in place rather than adding a row (E27). */
    public void refreshKeys(String newP256dhKey, String newAuthSecret, String newDeviceLabel) {
        this.p256dhKey = Objects.requireNonNull(newP256dhKey, "p256dhKey");
        this.authSecret = Objects.requireNonNull(newAuthSecret, "authSecret");
        this.deviceLabel = newDeviceLabel;
        this.failCount = 0;
        this.revokedAt = null;
    }

    public void recordSuccess(Instant at) {
        this.lastOkAt = Objects.requireNonNull(at, "at");
        this.failCount = 0;
    }

    /** @return whether this failure retired the endpoint. */
    public boolean recordFailure(Instant at) {
        this.failCount++;
        if (failCount >= FAILURE_LIMIT) {
            revoke(at);
            return true;
        }
        return false;
    }

    /** Revoked on {@code 410 Gone}, on the failure limit, or when a manager deactivates the user. */
    public void revoke(Instant at) {
        if (revokedAt == null) {
            this.revokedAt = Objects.requireNonNull(at, "at");
        }
    }

    public boolean isActive() {
        return revokedAt == null;
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

    public String getDeviceLabel() {
        return deviceLabel;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastOkAt() {
        return lastOkAt;
    }

    public int getFailCount() {
        return failCount;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }
}
