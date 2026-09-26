package in.ac.iitm.hostel.duty.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import in.ac.iitm.hostel.duty.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.ActiveProfiles;

/**
 * The profile Render and the standby jar both run under (architecture section 2.6).
 *
 * <p>The datasource still points at the Testcontainers database — {@code AbstractIntegrationTest}
 * overrides it — because what is under test here is the rest of the cloud profile: that it parses,
 * that its environment placeholders bind, and that the two settings a deployment gets wrong are
 * right. Everything it does to the database, Flyway included, is the same work the {@code local}
 * profile already proves.
 */
@SpringBootTest(
        properties = {
            // Stand in for the real values, which are Render environment variables (section 11.5).
            // Supplying them by their environment-variable names is the point: it is the binding
            // of `${GOOGLE_CLIENT_ID}` that is being exercised, not the value. The datasource
            // three satisfy ProfileCheck; the URL actually used is the container's, registered by
            // AbstractIntegrationTest and of higher precedence than this placeholder.
            "DATABASE_URL=jdbc:postgresql://replaced-by-testcontainers/duty_tracker",
            "DATABASE_USERNAME=duty_tracker",
            "DATABASE_PASSWORD=duty_tracker",
            "GOOGLE_CLIENT_ID=test-client-id",
            "GOOGLE_CLIENT_SECRET=test-client-secret"
        })
@ActiveProfiles(value = "cloud", inheritProfiles = false)
class CloudProfileIntegrationTest extends AbstractIntegrationTest {

    @Autowired private ServerProperties serverProperties;
    @Autowired private ClientRegistrationRepository clientRegistrations;

    @Test
    @DisplayName("the session cookie is Secure on the cloud profile — it never crosses plain http")
    void theSessionCookieIsSecure() {
        assertThat(serverProperties.getServlet().getSession().getCookie().getSecure()).isTrue();
    }

    @Test
    @DisplayName("with no PORT set the app binds 8080, which is the port in the localhost redirect URI")
    void theStandbyDefaultsToPort8080() {
        assertThat(serverProperties.getPort()).isEqualTo(8080);
    }

    @Test
    @DisplayName("the Google client comes from the environment, with no placeholder left to fall back on")
    void theGoogleClientComesFromTheEnvironment() {
        assertThat(clientRegistrations.findByRegistrationId("google").getClientId())
                .isEqualTo("test-client-id");
    }
}
