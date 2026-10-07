package duty.identity.web;

import static org.assertj.core.api.Assertions.assertThat;
import duty.identity.application.CurrentUser.SignedInUser;
import duty.identity.domain.AppUser.UserRole;
import duty.identity.web.SecurityConfig.RoleBasedLanding;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

/** After sign-in, a manager lands on the dashboard and an attender on their queue. */
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
