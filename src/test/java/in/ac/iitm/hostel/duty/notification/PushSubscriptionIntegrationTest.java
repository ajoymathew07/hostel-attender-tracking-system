package in.ac.iitm.hostel.duty.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.ac.iitm.hostel.duty.identity.infrastructure.AppUserRepository;
import in.ac.iitm.hostel.duty.notification.domain.PushSubscription;
import in.ac.iitm.hostel.duty.notification.infrastructure.PushSubscriptionRepository;
import in.ac.iitm.hostel.duty.support.AbstractIntegrationTest;
import in.ac.iitm.hostel.duty.support.Fixtures;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Web Push endpoints (data model section 3.7), covering the subscription corner cases of test plan
 * E27 and E28.
 *
 * <p>Nothing here talks to a push service — that is mocked at the HTTP boundary in a later PR
 * (section 12.7). What is tested is the row's own rules: identity, rotation and retirement.
 */
@SpringBootTest
class PushSubscriptionIntegrationTest extends AbstractIntegrationTest {

    @Autowired private PushSubscriptionRepository subscriptions;
    @Autowired private AppUserRepository users;

    private Long userId;

    @BeforeEach
    void provision() {
        this.userId = users.saveAndFlush(Fixtures.attender()).getId();
    }

    @Test
    @DisplayName("uq_push_endpoint: the same browser re-subscribing updates its row, never duplicates it — E27")
    void reSubscriptionUpdatesTheExistingRow() {
        String endpoint = "https://fcm.googleapis.com/fcm/send/" + Fixtures.unique("token");
        PushSubscription original = subscriptions.saveAndFlush(subscription(endpoint));

        assertThatThrownBy(() -> subscriptions.saveAndFlush(subscription(endpoint)))
                .describedAs("the endpoint is the device identity, so a second row is rejected")
                .isInstanceOf(DataIntegrityViolationException.class);

        // What the application does instead: rotate the keys on the row already there.
        PushSubscription existing = subscriptions.findByEndpoint(endpoint).orElseThrow();
        existing.refreshKeys("rotated-p256dh", "rotated-auth", "Pixel 7a");
        subscriptions.saveAndFlush(existing);

        PushSubscription reloaded = subscriptions.findByEndpoint(endpoint).orElseThrow();
        assertThat(reloaded.getId()).isEqualTo(original.getId());
        assertThat(reloaded.getP256dhKey()).isEqualTo("rotated-p256dh");
        assertThat(reloaded.getAuthSecret()).isEqualTo("rotated-auth");
        assertThat(reloaded.getDeviceLabel()).isEqualTo("Pixel 7a");
        assertThat(subscriptions.findByUserIdAndRevokedAtIsNull(userId)).hasSize(1);
    }

    @Test
    @DisplayName("three consecutive failures retire the endpoint, and a success resets the count")
    void failuresRetireTheEndpoint() {
        PushSubscription subscription =
                subscriptions.saveAndFlush(
                        subscription("https://push.example/" + Fixtures.unique("token")));
        Instant at = Instant.parse("2026-02-01T10:00:00Z");

        assertThat(subscription.recordFailure(at)).isFalse();
        assertThat(subscription.recordFailure(at)).isFalse();
        assertThat(subscription.getFailCount()).isEqualTo(2);

        // A delivery succeeds: the run of failures is broken.
        subscription.recordSuccess(Instant.parse("2026-02-01T10:05:00Z"));
        assertThat(subscription.getFailCount()).isZero();
        assertThat(subscription.isActive()).isTrue();

        assertThat(subscription.recordFailure(at)).isFalse();
        assertThat(subscription.recordFailure(at)).isFalse();
        assertThat(subscription.recordFailure(at))
                .describedAs("the third consecutive failure retires it")
                .isTrue();
        assertThat(subscription.isActive()).isFalse();
        subscriptions.saveAndFlush(subscription);

        assertThat(subscriptions.findByUserIdAndRevokedAtIsNull(userId))
                .describedAs("a revoked endpoint is not offered for further attempts — E28")
                .isEmpty();
    }

    @Test
    @DisplayName("revocation is idempotent and keeps the first revocation time")
    void revocationKeepsItsFirstTimestamp() {
        PushSubscription subscription =
                subscriptions.saveAndFlush(
                        subscription("https://push.example/" + Fixtures.unique("token")));

        subscription.revoke(Instant.parse("2026-02-01T10:00:00Z"));
        subscription.revoke(Instant.parse("2026-02-02T10:00:00Z"));

        assertThat(subscription.getRevokedAt()).isEqualTo(Instant.parse("2026-02-01T10:00:00Z"));

        // Re-subscribing from the same browser brings it back.
        subscription.refreshKeys("p256dh", "auth", null);
        assertThat(subscription.isActive()).isTrue();
        assertThat(subscription.getDeviceLabel()).isNull();
    }

    @Test
    @DisplayName("every column round-trips, including the nullable device label and timestamps")
    void allColumnsRoundTrip() {
        String endpoint = "https://push.example/" + Fixtures.unique("token");
        PushSubscription subscription = subscriptions.saveAndFlush(subscription(endpoint));
        subscription.recordSuccess(Instant.parse("2026-02-01T11:00:00Z"));
        subscriptions.saveAndFlush(subscription);

        PushSubscription reloaded = subscriptions.findById(subscription.getId()).orElseThrow();
        assertThat(reloaded.getUserId()).isEqualTo(userId);
        assertThat(reloaded.getEndpoint()).isEqualTo(endpoint);
        assertThat(reloaded.getP256dhKey()).isEqualTo("p256dh-key");
        assertThat(reloaded.getAuthSecret()).isEqualTo("auth-secret");
        assertThat(reloaded.getDeviceLabel()).isEqualTo("Redmi Note 12");
        assertThat(reloaded.getCreatedAt()).isEqualTo(Instant.parse("2026-01-15T08:00:00Z"));
        assertThat(reloaded.getLastOkAt()).isEqualTo(Instant.parse("2026-02-01T11:00:00Z"));
        assertThat(reloaded.getRevokedAt()).isNull();
        assertThat(reloaded.getFailCount()).isZero();
        assertThat(PushSubscription.FAILURE_LIMIT).isEqualTo(3);
    }

    private PushSubscription subscription(String endpoint) {
        return new PushSubscription(
                userId,
                endpoint,
                "p256dh-key",
                "auth-secret",
                "Redmi Note 12",
                Instant.parse("2026-01-15T08:00:00Z"));
    }
}
