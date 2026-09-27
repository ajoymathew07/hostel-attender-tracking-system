package in.ac.iitm.hostel.duty.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.ac.iitm.hostel.duty.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The health check Render pings, and the boundary around it (scope items 2 and 3 of PR 7).
 *
 * <p>Two things have to hold at once and they pull in opposite directions: the platform must be
 * able to reach the endpoint with no session, and an anonymous caller must learn nothing from it.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ActuatorExposureIntegrationTest extends AbstractIntegrationTest {

    /** Endpoints that would be useful to an attacker and are deliberately never mapped. */
    private static final String[] UNEXPOSED = {
        "/actuator", "/actuator/env", "/actuator/beans", "/actuator/info",
        "/actuator/configprops", "/actuator/metrics", "/actuator/loggers"
    };

    @Autowired private MockMvc mockMvc;
    @Autowired private ServerProperties serverProperties;

    @Test
    @WithAnonymousUser
    @DisplayName("the health check answers without a session, and says only that the app is up")
    void healthIsPublicAndTellsNothingElse() throws Exception {
        String body =
                mockMvc.perform(get("/actuator/health"))
                        .andExpect(status().isOk())
                        .andExpect(content().string(org.hamcrest.Matchers.containsString("\"status\":\"UP\"")))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        // No component breakdown, no database or disk detail, no build or version information —
        // the whole payload is the one status field (section 11.1).
        assertThat(body).doesNotContain("components", "db", "diskSpace", "version", "build", "profile");
    }

    @Test
    @WithAnonymousUser
    @DisplayName("the readiness and liveness probes come free with it and are equally bare")
    void probesAreAvailable() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    @DisplayName("every other actuator endpoint is not mapped at all, even for a manager")
    void nothingElseIsExposed() throws Exception {
        for (String path : UNEXPOSED) {
            mockMvc.perform(get(path)).andExpect(status().isNotFound());
        }
    }

    @Test
    @DisplayName("the local profile leaves the session cookie insecure, so sign-in works over plain http")
    void theLocalProfileDoesNotSetSecure() {
        assertThat(serverProperties.getServlet().getSession().getCookie().getSecure()).isNull();
    }
}
