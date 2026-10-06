package duty.identity.web;

import static org.assertj.core.api.Assertions.assertThat;

import duty.identity.application.SignedInUser;
import duty.identity.domain.UserRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

/**
 * Where a sign-in lands (section 10.4).
 *
 * <p>Before this, every successful sign-in went to {@code /}, which is manager-only — so an
 * attender's first sight of the system was a 403. There is one login page for everyone (ADR-002)
 * but two applications behind it, and this is the seam.
 */
class RoleBasedLandingTest {

    private final RoleBasedLanding landing = new RoleBasedLanding();

    private static Authentication signedIn(UserRole role) {
        return new TestingAuthenticationToken(
                new SignedInUser(7L, "user@example.com", "User", role), null, "ROLE_" + role.name());
    }

    private String landingUrlFor(UserRole role) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/login/oauth2/code/google");
        MockHttpServletResponse response = new MockHttpServletResponse();
        landing.onAuthenticationSuccess(request, response, signedIn(role));
        return response.getRedirectedUrl();
    }

    @Test
    @DisplayName("an attender lands on their queue, not on the manager-only dashboard")
    void anAttenderLandsOnTheirQueue() throws Exception {
        assertThat(landingUrlFor(UserRole.ATTENDER)).isEqualTo("/m");
    }

    @Test
    @DisplayName("a manager lands on the desktop dashboard")
    void aManagerLandsOnTheDashboard() throws Exception {
        assertThat(landingUrlFor(UserRole.MANAGER)).isEqualTo("/");
    }
}
