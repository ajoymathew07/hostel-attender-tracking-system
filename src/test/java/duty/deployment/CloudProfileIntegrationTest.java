package duty.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import duty.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.ActiveProfiles;

/**
 * The {@code cloud} profile loads and its key settings are right. The database is still the test
 * container.
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
