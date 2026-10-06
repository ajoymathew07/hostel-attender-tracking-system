package duty.identity;

import static org.assertj.core.api.Assertions.assertThat;

import duty.audit.application.CorrelationId;
import duty.audit.domain.AuditEvent;
import duty.audit.infrastructure.AuditEventRepository;
import duty.identity.application.OidcIdentity;
import duty.identity.application.SignInService;
import duty.identity.domain.AppUser;
import duty.identity.infrastructure.AppUserRepository;
import duty.support.AbstractIntegrationTest;
import duty.support.Fixtures;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Every sign-in is audited, including refusals, and the audit row really commits. */
@SpringBootTest
class SignInAuditIntegrationTest extends AbstractIntegrationTest {

    @Autowired private SignInService signIn;
    @Autowired private AppUserRepository users;
    @Autowired private AuditEventRepository auditEvents;

    /** Runs one sign-in under its own correlation id and returns its audit rows. */
    private List<AuditEvent> auditOf(String email, boolean emailVerified) {
        String correlation = CorrelationId.generate();
        CorrelationId.runWith(correlation, () -> signIn.admit(new OidcIdentity(email, emailVerified)));
        return auditEvents.findByCorrelationIdOrderByOccurredAtAsc(correlation);
    }

    @Test
    @DisplayName("an admitted sign-in commits a SIGN_IN row naming the user")
    void anAdmittedSignInIsAudited() {
        AppUser ravi = users.saveAndFlush(Fixtures.attender());

        List<AuditEvent> written = auditOf(ravi.getEmail(), true);

        assertThat(written).singleElement().satisfies(event -> {
            assertThat(event.getEventType()).isEqualTo("SIGN_IN");
            assertThat(event.getActorId()).isEqualTo(ravi.getId());
            assertThat(event.getActorRole()).isEqualTo("ATTENDER");
            assertThat(event.getEntityId()).isEqualTo(ravi.getId());
        });
        assertThat(users.findById(ravi.getId()).orElseThrow().getLastLoginAt()).isNotNull();
    }

    @Test
    @DisplayName("a refused sign-in commits a row carrying the email that was refused, and creates no user — E20a")
    void aRefusedSignInIsAuditedAndCreatesNothing() {
        String stranger = Fixtures.unique("stranger") + "@gmail.com";
        long usersBefore = users.count();

        List<AuditEvent> written = auditOf("  " + stranger.toUpperCase() + "  ", true);

        assertThat(written).singleElement().satisfies(event -> {
            assertThat(event.getEventType()).isEqualTo("SIGN_IN_REJECTED");
            assertThat(event.getDetail()).contains(stranger.toLowerCase()).contains("NOT_PROVISIONED");
            assertThat(event.getActorId()).isNull();
        });
        assertThat(users.count()).describedAs("no self-registration").isEqualTo(usersBefore);
        assertThat(users.findByEmail(stranger.toLowerCase())).isEmpty();
    }

    @Test
    @DisplayName("a deactivated user's attempt is audited against their row — E20b")
    void aDeactivatedUsersAttemptIsAudited() {
        AppUser ravi = Fixtures.attender();
        ravi.deactivate();
        ravi = users.saveAndFlush(ravi);

        List<AuditEvent> written = auditOf(ravi.getEmail(), true);

        AppUser deactivated = ravi;
        assertThat(written).singleElement().satisfies(event -> {
            assertThat(event.getEventType()).isEqualTo("SIGN_IN_REJECTED");
            assertThat(event.getDetail()).contains("DEACTIVATED");
            assertThat(event.getEntityId()).isEqualTo(deactivated.getId());
        });
    }

    @Test
    @DisplayName("an unverified claim is refused and audited even though the address is on the allowlist")
    void anUnverifiedClaimIsAudited() {
        AppUser ravi = users.saveAndFlush(Fixtures.attender());

        List<AuditEvent> written = auditOf(ravi.getEmail(), false);

        assertThat(written).singleElement().satisfies(event ->
                assertThat(event.getDetail()).contains("EMAIL_NOT_VERIFIED"));
        assertThat(users.findById(ravi.getId()).orElseThrow().getLastLoginAt())
                .describedAs("a refused attempt is not a login")
                .isNull();
    }
}
