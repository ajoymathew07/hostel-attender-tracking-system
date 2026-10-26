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
import duty.workflow.application.ManualAssignmentService;
import duty.workflow.infrastructure.ComplaintRepository;
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

/** The complaint history page renders for the manager, and act-as works from it. */
@SpringBootTest
@AutoConfigureMockMvc
class ComplaintHistoryControllerIntegrationTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ManualAssignmentService assignment;
    @Autowired private ComplaintRepository complaints;
    @Autowired private AppUserRepository users;

    private TestingAuthenticationToken asManager;
    private TestingAuthenticationToken asRavi;
    private AppUser ravi;
    private long complaintId;

    @BeforeEach
    void assignAComplaint() {
        AppUser manager = users.saveAndFlush(Fixtures.manager());
        ravi = users.saveAndFlush(Fixtures.attender());
        complaintId = complaints.saveAndFlush(Fixtures.complaintInTriage()).getId();
        asManager = Fixtures.signIn(manager);
        assignment.assign(complaintId, ravi.getId());
        asRavi = Fixtures.signIn(ravi);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("the history shows the assignment and offers to act for the holder")
    void theHistoryPageRenders() throws Exception {
        mockMvc.perform(get("/complaints/" + complaintId).with(authentication(asManager)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("TASK_ASSIGNED")))
                .andExpect(content().string(containsString("/act-as")))
                .andExpect(content().string(containsString(ravi.getDisplayName())));
    }

    @Test
    @DisplayName("an attender cannot open it, and an unknown complaint is a 404")
    void accessIsChecked() throws Exception {
        mockMvc.perform(get("/complaints/" + complaintId).with(authentication(asRavi)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/complaints/999999").with(authentication(asManager)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("act-as from the page moves the complaint; a refused step is shown on the same page")
    void actAsFromThePage() throws Exception {
        mockMvc.perform(
                        post("/complaints/" + complaintId + "/act-as")
                                .with(authentication(asManager))
                                .with(csrf())
                                .param("step", "ATTENDER_START"))
                .andExpect(redirectedUrl("/complaints/" + complaintId))
                .andExpect(flash().attributeExists("message"));

        mockMvc.perform(
                        post("/complaints/" + complaintId + "/act-as")
                                .with(authentication(asManager))
                                .with(csrf())
                                .param("step", "MARK_RESOLVED"))
                .andExpect(redirectedUrl("/complaints/" + complaintId))
                .andExpect(flash().attributeExists("error"));
    }
}
