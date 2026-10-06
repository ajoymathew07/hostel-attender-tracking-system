package duty.identity.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import duty.support.AbstractIntegrationTest;
import duty.support.Fixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Our security configuration: which paths are open, and who reaches the manager's screen. Uses
 * {@code @WithMockUser}; the OAuth flow itself is Spring's.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SecurityFilterChainIntegrationTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;

    @Test
    @WithAnonymousUser
    @DisplayName("an anonymous request to any application URL lands on the login page")
    void everythingElseRequiresASession() throws Exception {
        mockMvc.perform(get("/")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrlPattern("**/login"));
        mockMvc.perform(get("/admin/attenders"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("the login page is the one thing an unauthenticated visitor sees — and it has no password field")
    void theLoginPageIsPublic() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(view().name("identity/login"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/oauth2/authorization/google")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("type=\"password\""))));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("the response carries the headers of section 11.6")
    void securityHeadersAreSet() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().exists("Content-Security-Policy"))
                .andExpect(header().exists("Referrer-Policy"));
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    @DisplayName("a manager reaches the provisioning screen and it renders")
    void aManagerReachesTheProvisioningScreen() throws Exception {
        mockMvc.perform(get("/admin/attenders"))
                .andExpect(status().isOk())
                .andExpect(view().name("identity/attenders"))
                .andExpect(model().attributeExists("users", "roles", "form"));
    }

    @Test
    @WithMockUser(roles = "ATTENDER")
    @DisplayName("an attender is refused the provisioning screen — section 11.3")
    void anAttenderIsRefusedTheProvisioningScreen() throws Exception {
        mockMvc.perform(get("/admin/attenders")).andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/attenders").with(csrf()).param("displayName", "Sneaky").param("email", "sneaky@example.com").param("role", "MANAGER"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    @DisplayName("a manager adds a user through the screen, and the form round-trips")
    void aManagerAddsAUserThroughTheScreen() throws Exception {
        mockMvc.perform(
                        post("/admin/attenders")
                                .with(csrf())
                                .param("displayName", "Selvi")
                                .param("email", Fixtures.unique("screen") + "@example.com")
                                .param("role", "ATTENDER"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/attenders"));
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    @DisplayName("an invalid address is shown as a form error rather than reaching the service")
    void anInvalidAddressIsAFormError() throws Exception {
        mockMvc.perform(
                        post("/admin/attenders")
                                .with(csrf())
                                .param("displayName", "Selvi")
                                .param("email", "not-an-address")
                                .param("role", "ATTENDER"))
                .andExpect(status().isOk())
                .andExpect(view().name("identity/attenders"))
                .andExpect(model().attributeHasFieldErrors("form", "email"));
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    @DisplayName("a state-changing post without a CSRF token is refused — section 11.6")
    void csrfIsRequired() throws Exception {
        mockMvc.perform(post("/admin/attenders").param("displayName", "Selvi").param("email", "csrf@example.com").param("role", "ATTENDER"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ATTENDER")
    @DisplayName("an attender who opens the dashboard is sent to their queue, not an error page")
    void anAttenderOpeningTheDashboardIsRedirectedToTheirQueue() throws Exception {
        // The landing handler only fires at sign-in, so a bookmark or the back button would
        // otherwise drop an attender on an error page for a screen they never chose to open.
        mockMvc.perform(get("/")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/m"));
        mockMvc.perform(get("/complaints")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/m"));
    }
}
