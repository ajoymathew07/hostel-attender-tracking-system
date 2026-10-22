package duty.workflow.web;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import duty.identity.domain.AppUser;
import duty.identity.infrastructure.AppUserRepository;
import duty.support.AbstractIntegrationTest;
import duty.support.Fixtures;
import duty.workflow.application.SpecialDutyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

/** The manager's /points page and the attender's /m/duties page render and accept their forms. */
@SpringBootTest
@AutoConfigureMockMvc
class DutiesControllerIntegrationTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private SpecialDutyService duties;
    @Autowired private AppUserRepository users;

    private TestingAuthenticationToken asManager;
    private TestingAuthenticationToken asRavi;
    private long dutyId;

    @BeforeEach
    void postADuty() {
        AppUser manager = users.saveAndFlush(Fixtures.manager());
        AppUser ravi = users.saveAndFlush(Fixtures.attender());
        asManager = Fixtures.signIn(manager);
        dutyId = duties.post("Fumigation round", null, 3, 1, null);
        asRavi = Fixtures.signIn(ravi);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("the manager's page shows the forms and the open duty")
    void theManagerSeesPointsAndDuties() throws Exception {
        mockMvc.perform(get("/points").with(authentication(asManager)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Post a special duty")))
                .andExpect(content().string(containsString("Fumigation round")));
    }

    @Test
    @DisplayName("an attender cannot open the manager's page")
    void anAttenderIsRefusedThePointsPage() throws Exception {
        mockMvc.perform(get("/points").with(authentication(asRavi))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an attender sees the duty, claims it, and is told when it is no longer open")
    void anAttenderClaimsADuty() throws Exception {
        mockMvc.perform(get("/m/duties").with(authentication(asRavi)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Fumigation round")));

        mockMvc.perform(post("/m/duties/" + dutyId + "/claim").with(authentication(asRavi)).with(csrf()))
                .andExpect(redirectedUrl("/m/duties"))
                .andExpect(flash().attributeExists("message"));

        mockMvc.perform(post("/m/duties/" + dutyId + "/claim").with(authentication(asRavi)).with(csrf()))
                .andExpect(redirectedUrl("/m/duties"))
                .andExpect(flash().attributeExists("error"));
    }

    @Test
    @DisplayName("the manager logs a penalty from the form")
    void theManagerLogsAPenalty() throws Exception {
        AppUser kumar = users.saveAndFlush(Fixtures.attender());

        mockMvc.perform(
                        post("/points/penalty")
                                .with(authentication(asManager))
                                .with(csrf())
                                .param("attenderId", String.valueOf(kumar.getId()))
                                .param("requestId", Fixtures.unique("form")))
                .andExpect(redirectedUrl("/points"))
                .andExpect(flash().attribute("message", containsString("Penalty")));
    }
}
