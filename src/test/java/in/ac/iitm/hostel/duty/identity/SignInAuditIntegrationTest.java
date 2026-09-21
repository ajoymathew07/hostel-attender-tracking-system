package in.ac.iitm.hostel.duty.identity;

import static org.assertj.core.api.Assertions.assertThat;

import in.ac.iitm.hostel.duty.audit.application.CorrelationId;
import in.ac.iitm.hostel.duty.audit.domain.AuditEvent;
import in.ac.iitm.hostel.duty.audit.infrastructure.AuditEventRepository;
import in.ac.iitm.hostel.duty.identity.application.OidcIdentity;
import in.ac.iitm.hostel.duty.identity.application.SignInService;
import in.ac.iitm.hostel.duty.identity.domain.AppUser;
import in.ac.iitm.hostel.duty.identity.infrastructure.AppUserRepository;
import in.ac.iitm.hostel.duty.support.AbstractIntegrationTest;
import in.ac.iitm.hostel.duty.support.Fixtures;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Sign-ins are audited, including the refused ones (section 11.7).
 *
 * <p>Against a real database, because the property that matters is not that a method was called but
 * that a <em>row committed</em> — and for a refusal that is the interesting case: the decision
 * throws nobody out of a transaction, so the record of the attempt survives (see {@code
 * SignInOutcome}).
 */
@SpringBootTest
class SignInAuditIntegrationTest extends AbstractIntegrationTest {

    @Autowired private SignInService signIn;
    @Autowired private AppUserRepository users;
    @Autowired private AuditEventRepository auditEvents;

    /** Runs one sign-in attempt under its own correlation, and returns what it wrote to the log. */
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
