package in.ac.iitm.hostel.duty.workflow.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.ac.iitm.hostel.duty.identity.domain.AppUser;
import in.ac.iitm.hostel.duty.identity.infrastructure.AppUserRepository;
import in.ac.iitm.hostel.duty.ledger.infrastructure.PointLedgerRepository;
import in.ac.iitm.hostel.duty.support.AbstractIntegrationTest;
import in.ac.iitm.hostel.duty.support.Fixtures;
import in.ac.iitm.hostel.duty.workflow.application.ManualAssignmentService;
import in.ac.iitm.hostel.duty.workflow.infrastructure.ComplaintRepository;
import in.ac.iitm.hostel.duty.workflow.infrastructure.TaskRepository;
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

/**
 * The docket and resolve routes at the HTTP edge.
 *
 * <p>The rules themselves are proved at the service layer in {@code TaskResolutionIntegrationTest}.
 * What this class adds is that the two form posts exist, that the walking skeleton really does run
 * end to end from the browser, and that somebody else's task is a 403 rather than a page.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TaskResolutionControllerIntegrationTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ManualAssignmentService assignment;
    @Autowired private ComplaintRepository complaints;
    @Autowired private TaskRepository tasks;
    @Autowired private PointLedgerRepository ledger;
    @Autowired private AppUserRepository users;

    private TestingAuthenticationToken asRavi;
    private TestingAuthenticationToken asKumar;
    private AppUser ravi;
    private AppUser kumar;
    private long taskId;

    @BeforeEach
    void assignSomeWork() {
        AppUser manager = users.saveAndFlush(Fixtures.manager());
        ravi = users.saveAndFlush(Fixtures.attender());
        kumar = users.saveAndFlush(Fixtures.attender());

        long complaintId = complaints.saveAndFlush(Fixtures.complaint()).getId();
        Fixtures.signIn(manager);
        assignment.assign(complaintId, ravi.getId());
        taskId = tasks.findByComplaintId(complaintId).orElseThrow().getId();

        asRavi = Fixtures.signIn(ravi);
        asKumar = Fixtures.signIn(kumar);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("file the docket, then resolve: the skeleton walks from the phone to the ledger")
    void docketThenResolve() throws Exception {
        // START and DEFECT? — the two steps of section 10.2's flow that PR 11 adds. Before it,
        // the docket could be filed straight from ASSIGNED because no table said otherwise.
        mockMvc.perform(post("/m/tasks/{id}/start", taskId).with(authentication(asRavi)).with(csrf()))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(post("/m/tasks/{id}/defect", taskId).with(authentication(asRavi)).with(csrf()))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(
                        post("/m/tasks/{id}/docket", taskId)
                                .param("docketNo", "EU/2026/00417")
                                .with(authentication(asRavi))
                                .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/m/tasks/" + taskId));

        mockMvc.perform(get("/m/tasks/{id}", taskId).with(authentication(asRavi)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("EU/2026/00417")))
                .andExpect(content().string(containsString("Resolve")));

        mockMvc.perform(
                        post("/m/tasks/{id}/resolve", taskId)
                                .with(authentication(asRavi))
                                .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/m"));

        assertThat(ledger.balanceOf(ravi.getId(), null)).isEqualTo(2L);

        mockMvc.perform(get("/m").with(authentication(asRavi)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("points this month")));
    }

    @Test
    @DisplayName("resolving without a docket comes back as a sentence, not a stack trace")
    void resolvingWithoutADocketRedirectsWithAnError() throws Exception {
        mockMvc.perform(
                        post("/m/tasks/{id}/resolve", taskId)
                                .with(authentication(asRavi))
                                .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/m"));

        assertThat(ledger.balanceOf(ravi.getId(), null)).isZero();
    }

    @Test
    @DisplayName("another attender's resolve is a 403 at the edge, and credits nobody")
    void aStrangerIsRefused() throws Exception {
        mockMvc.perform(
                        post("/m/tasks/{id}/docket", taskId)
                                .param("docketNo", "EU/2026/00417")
                                .with(authentication(asKumar))
                                .with(csrf()))
                .andExpect(status().isForbidden());

        mockMvc.perform(
                        post("/m/tasks/{id}/resolve", taskId)
                                .with(authentication(asKumar))
                                .with(csrf()))
                .andExpect(status().isForbidden());

        assertThat(ledger.balanceOf(kumar.getId(), null)).isZero();
        assertThat(ledger.balanceOf(ravi.getId(), null)).isZero();
    }
}
