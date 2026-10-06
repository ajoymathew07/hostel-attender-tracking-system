package duty.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import duty.audit.application.AuditEntry;
import duty.audit.application.AuditTrail;
import duty.identity.domain.AppUser;
import duty.identity.domain.UserRole;
import duty.identity.infrastructure.AppUserRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** The allowlist decision, unit-tested directly rather than through the OAuth flow. */
@ExtendWith(MockitoExtension.class)
class SignInServiceTest {

    @Mock private AppUserRepository users;
    @Mock private AuditTrail audit;

    private SignInService signIn() {
        return new SignInService(users, audit);
    }

    /** A user row with a stubbed id, since {@code AppUser} has no id setter. */
    private static AppUser attender(String email) {
        AppUser user =
                spy(new AppUser("Ravi", UserRole.ATTENDER, email, Instant.parse("2026-01-01T00:00:00Z"), null));
        when(user.getId()).thenReturn(7L);
        return user;
    }

    @Test
    @DisplayName("an allowlisted, active, verified email is admitted with the role from app_user")
    void admitsAnAllowlistedUser() {
        AppUser ravi = attender("ravi@example.com");
        when(users.findByEmail("ravi@example.com")).thenReturn(Optional.of(ravi));

        SignInOutcome outcome = signIn().admit(new OidcIdentity("ravi@example.com", true));

        assertThat(outcome.admitted()).isTrue();
        assertThat(outcome.user().getRole()).isEqualTo(UserRole.ATTENDER);
        assertThat(outcome.user().getEmail()).isEqualTo("ravi@example.com");
        assertThat(ravi.getLastLoginAt()).describedAs("the sign-in is recorded").isNotNull();
    }

    @Test
    @DisplayName("a Google account that is not on the allowlist is rejected, and no row is created — E20a")
    void rejectsAnUnknownGoogleAccount() {
        when(users.findByEmail("stranger@gmail.com")).thenReturn(Optional.empty());

        SignInOutcome outcome = signIn().admit(new OidcIdentity("stranger@gmail.com", true));

        assertThat(outcome.admitted()).isFalse();
        assertThat(outcome.rejection()).isEqualTo(SignInRejection.NOT_PROVISIONED);
        verify(users, never()).save(any());
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("an allowlisted but deactivated user cannot sign in — E20b")
    void rejectsADeactivatedUser() {
        AppUser ravi = attender("ravi@example.com");
        ravi.deactivate();
        when(users.findByEmail("ravi@example.com")).thenReturn(Optional.of(ravi));

        SignInOutcome outcome = signIn().admit(new OidcIdentity("ravi@example.com", true));

        assertThat(outcome.admitted()).isFalse();
        assertThat(outcome.rejection()).isEqualTo(SignInRejection.DEACTIVATED);
        assertThat(ravi.getLastLoginAt()).describedAs("a refused attempt is not a login").isNull();
    }

    @Test
    @DisplayName("an unverified email claim is refused even when the address matches a row")
    void rejectsAnUnverifiedEmailClaim() {
        SignInOutcome outcome = signIn().admit(new OidcIdentity("ravi@example.com", false));

        assertThat(outcome.admitted()).isFalse();
        assertThat(outcome.rejection()).isEqualTo(SignInRejection.EMAIL_NOT_VERIFIED);
        verify(users, never()).findByEmail(anyString());
    }

    @Test
    @DisplayName("a missing email claim is refused rather than matched against anything")
    void rejectsAMissingEmailClaim() {
        SignInOutcome outcome = signIn().admit(new OidcIdentity(null, true));

        assertThat(outcome.admitted()).isFalse();
        assertThat(outcome.rejection()).isEqualTo(SignInRejection.NOT_PROVISIONED);
        verify(users, never()).findByEmail(anyString());
    }

    @Test
    @DisplayName("case and surrounding whitespace normalise to the same person — E20c")
    void normalisesTheEmailBeforeComparing() {
        AppUser ravi = attender("ravi@example.com");
        when(users.findByEmail("ravi@example.com")).thenReturn(Optional.of(ravi));

        assertThat(signIn().admit(new OidcIdentity("  Ravi@Example.COM  ", true)).admitted()).isTrue();
    }

    @Test
    @DisplayName("a near-match is not a match: normalisation trims and lower-cases, nothing else")
    void doesNotAdmitANearMatch() {
        when(users.findByEmail("ra.vi@example.com")).thenReturn(Optional.empty());

        SignInOutcome outcome = signIn().admit(new OidcIdentity("ra.vi@example.com", true));

        assertThat(outcome.admitted()).isFalse();
        assertThat(outcome.rejection()).isEqualTo(SignInRejection.NOT_PROVISIONED);
    }

    @Test
    @DisplayName("a Unicode address that lower-cases onto an allowlisted one is not looked up at all")
    void refusesAnAddressThatCaseFoldingWouldCollideWithARow() {
        // U+212A KELVIN SIGN lower-cases to 'k', so this normalises to exactly "kumar@example.com".
        SignInOutcome outcome = signIn().admit(new OidcIdentity("Kumar@example.com", true));

        assertThat(outcome.admitted()).isFalse();
        assertThat(outcome.rejection()).isEqualTo(SignInRejection.NOT_PROVISIONED);
        verify(users, never())
                .findByEmail(anyString());
    }

    @Test
    @DisplayName("an admitted sign-in is audited against the user")
    void auditsAnAdmittedSignIn() {
        AppUser ravi = attender("ravi@example.com");
        when(users.findByEmail("ravi@example.com")).thenReturn(Optional.of(ravi));

        signIn().admit(new OidcIdentity("ravi@example.com", true));

        AuditEntry entry = capturedEntry();
        assertThat(entry.eventType()).isEqualTo("SIGN_IN");
        assertThat(entry.entityType()).isEqualTo("app_user");
        assertThat(entry.actorRole()).isEqualTo("ATTENDER");
    }

    @Test
    @DisplayName("a refused sign-in is audited with the email that was refused — section 11.7")
    void auditsARefusedSignInWithTheEmail() {
        when(users.findByEmail("stranger@gmail.com")).thenReturn(Optional.empty());

        signIn().admit(new OidcIdentity("  Stranger@Gmail.com ", true));

        AuditEntry entry = capturedEntry();
        assertThat(entry.eventType()).isEqualTo("SIGN_IN_REJECTED");
        assertThat(entry.detail()).contains("stranger@gmail.com").contains("NOT_PROVISIONED");
        assertThat(entry.actorId()).describedAs("nobody authenticated, so there is no actor").isNull();
    }

    @Test
    @DisplayName("an address carrying JSON punctuation cannot corrupt the audit record")
    void escapesTheRefusedAddress() throws Exception {
        String hostile = "a\"b\\c\u0001@example.com";
        when(users.findByEmail(hostile)).thenReturn(Optional.empty());

        signIn().admit(new OidcIdentity(hostile, true));

        JsonNode detail = new ObjectMapper().readTree(capturedEntry().detail());
        assertThat(detail.get("email").asText())
                .describedAs("the log reads back exactly what was refused")
                .isEqualTo(hostile);
        assertThat(detail.get("reason").asText()).isEqualTo("NOT_PROVISIONED");
    }

    private AuditEntry capturedEntry() {
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).record(captor.capture());
        return captor.getValue();
    }
}
