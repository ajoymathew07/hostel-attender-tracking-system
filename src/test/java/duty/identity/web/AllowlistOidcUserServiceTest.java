package duty.identity.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import duty.identity.application.CurrentUser.SignedInUser;
import duty.identity.application.SignInService.OidcIdentity;
import duty.identity.application.SignInService.SignInOutcome;
import duty.identity.application.SignInService.SignInRejection;
import duty.identity.application.SignInService;
import duty.identity.domain.AppUser.UserRole;
import duty.identity.web.SecurityConfig.AllowlistOidcUserService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * The OIDC adapter: turns a refusal into Spring Security's exception, and takes authorities from
 * {@code app_user}, not the token.
 */
@ExtendWith(MockitoExtension.class)
class AllowlistOidcUserServiceTest {

    @Mock private SignInService signIn;

    private static OidcUserRequest request(Map<String, Object> claims) {
        ClientRegistration google =
                CommonOAuth2Provider.GOOGLE.getBuilder("google").clientId("id").clientSecret("secret").build();
        OidcIdToken idToken =
                new OidcIdToken(
                        "id-token", Instant.EPOCH, Instant.EPOCH.plusSeconds(3600), claims);
        OAuth2AccessToken accessToken =
                new OAuth2AccessToken(
                        OAuth2AccessToken.TokenType.BEARER,
                        "access-token",
                        Instant.EPOCH,
                        Instant.EPOCH.plusSeconds(3600));
        return new OidcUserRequest(google, accessToken, idToken);
    }

    /** What Google returns, including extra claims that must be ignored. */
    private static OidcUser googleUser(Map<String, Object> claims) {
        List<GrantedAuthority> asserted = List.of(new SimpleGrantedAuthority("ROLE_ADMIN"));
        return new DefaultOidcUser(asserted, new OidcIdToken("id-token", Instant.EPOCH, Instant.EPOCH.plusSeconds(3600), claims));
    }

    private AllowlistOidcUserService service(Map<String, Object> claims) {
        return new AllowlistOidcUserService(signIn, userRequest -> googleUser(claims));
    }

    @Test
    @DisplayName("an admitted user gets the role from app_user, never the one Google asserted")
    void authoritiesComeFromTheAllowlistRow() {
        Map<String, Object> claims =
                Map.of("sub", "1", "email", "manager@example.com", "email_verified", true, "name", "Google Name");
        when(signIn.admit(any()))
                .thenReturn(
                        SignInOutcome.admitted(
                                new SignedInUser(3L, "manager@example.com", "Hostel Manager", UserRole.MANAGER)));

        OidcUser principal = service(claims).loadUser(request(claims));

        assertThat(principal.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_MANAGER");
        assertThat(principal).isInstanceOf(SignedInUser.class);
        assertThat(((SignedInUser) principal).getDisplayName())
                .describedAs("the provisioned name, not the Google profile name")
                .isEqualTo("Hostel Manager");
        assertThat(principal.getIdToken()).describedAs("the flow's token is carried through").isNotNull();
        assertThat(principal.getName()).describedAs("the allowlist key identifies the principal").isEqualTo("manager@example.com");
        assertThat(principal.getClaims()).containsEntry("email", "manager@example.com");
        assertThat(principal.getAttributes()).isEqualTo(principal.getClaims());
        assertThat(principal.toString()).contains("MANAGER");
    }

    @Test
    @DisplayName("only the verified email claim crosses the boundary")
    void passesOnlyTheVerifiedEmailClaim() {
        Map<String, Object> claims =
                Map.of("sub", "1", "email", "Ravi@Example.com", "email_verified", true, "hd", "example.com");
        when(signIn.admit(any()))
                .thenReturn(SignInOutcome.admitted(new SignedInUser(9L, "ravi@example.com", "Ravi", UserRole.ATTENDER)));

        service(claims).loadUser(request(claims));

        ArgumentCaptor<OidcIdentity> captor = ArgumentCaptor.forClass(OidcIdentity.class);
        org.mockito.Mockito.verify(signIn).admit(captor.capture());
        assertThat(captor.getValue().email()).isEqualTo("Ravi@Example.com");
        assertThat(captor.getValue().emailVerified()).isTrue();
    }

    @Test
    @DisplayName("an email_verified claim sent as a string is read, not assumed true")
    void readsAStringEmailVerifiedClaim() {
        Map<String, Object> claims = Map.of("sub", "1", "email", "ravi@example.com", "email_verified", "false");
        when(signIn.admit(any())).thenReturn(SignInOutcome.refused(SignInRejection.EMAIL_NOT_VERIFIED, "ravi@example.com"));

        assertThatThrownBy(() -> service(claims).loadUser(request(claims)))
                .isInstanceOf(OAuth2AuthenticationException.class);

        ArgumentCaptor<OidcIdentity> captor = ArgumentCaptor.forClass(OidcIdentity.class);
        org.mockito.Mockito.verify(signIn).admit(captor.capture());
        assertThat(captor.getValue().emailVerified()).isFalse();
    }

    @Test
    @DisplayName("a missing email_verified claim is treated as not verified")
    void treatsAMissingClaimAsUnverified() {
        Map<String, Object> claims = Map.of("sub", "1", "email", "ravi@example.com");
        when(signIn.admit(any())).thenReturn(SignInOutcome.refused(SignInRejection.EMAIL_NOT_VERIFIED, "ravi@example.com"));

        assertThatThrownBy(() -> service(claims).loadUser(request(claims)))
                .isInstanceOf(OAuth2AuthenticationException.class);

        ArgumentCaptor<OidcIdentity> captor = ArgumentCaptor.forClass(OidcIdentity.class);
        org.mockito.Mockito.verify(signIn).admit(captor.capture());
        assertThat(captor.getValue().emailVerified()).isFalse();
    }

    @Test
    @DisplayName("a refusal becomes NOT_PROVISIONED, whichever reason it was — section 9.7")
    void everyRefusalSurfacesAsNotProvisioned() {
        Map<String, Object> claims = Map.of("sub", "1", "email", "gone@example.com", "email_verified", true);

        for (SignInRejection rejection : SignInRejection.values()) {
            when(signIn.admit(any())).thenReturn(SignInOutcome.refused(rejection, "gone@example.com"));

            OAuth2AuthenticationException thrown =
                    catchThrowableOfType(
                            () -> service(claims).loadUser(request(claims)),
                            OAuth2AuthenticationException.class);

            assertThat(thrown.getError().getErrorCode()).isEqualTo("NOT_PROVISIONED");
            assertThat(thrown.getError().getDescription())
                    .describedAs("the reason is audited, not disclosed to the caller")
                    .doesNotContain(rejection.name());
        }
    }
}
