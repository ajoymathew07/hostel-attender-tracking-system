package in.ac.iitm.hostel.duty.workflow.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import in.ac.iitm.hostel.duty.identity.domain.AppUser;
import in.ac.iitm.hostel.duty.identity.infrastructure.AppUserRepository;
import in.ac.iitm.hostel.duty.support.AbstractIntegrationTest;
import in.ac.iitm.hostel.duty.support.Fixtures;
import in.ac.iitm.hostel.duty.workflow.domain.ComplaintDomain;
import in.ac.iitm.hostel.duty.workflow.infrastructure.ComplaintRepository;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The manager desktop at the HTTP edge (sections 10.3, 10.4).
 *
 * <p>{@code /} is the route PR 3 deliberately left unbuilt, so the first assertion here is simply
 * that a signed-in manager lands on something real rather than a 404.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ManagerDashboardControllerIntegrationTest extends AbstractIntegrationTest {

    private static final String CSV =
            "Timestamp,Email Address,Name,Roll Number,Phone Number,Room Number,Describe the issue\n"
                    + "2026/02/14 11:00:00,screen@example.com,Screen Student,CE21B900,9000000900,1204,"
                    + "Water leaking from the ceiling fan\n";

    /** `ceiling` is Civil and `fan` is Electrical, level: exactly what the triage panel is for. */
    private static final String AMBIGUOUS_CSV =
            "Timestamp,Email Address,Room Number,Describe the issue\n"
                    + "2026/02/15 09:00:00,triage.screen@example.com,1205,water coming from ceiling fan\n";

    @Autowired private MockMvc mockMvc;
    @Autowired private ComplaintRepository complaints;
    @Autowired private AppUserRepository users;

    private static MockMultipartFile file(String name, String contentType, String body) {
        return new MockMultipartFile("file", name, contentType, body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    @DisplayName("the tiles are counted now, and the screen says it refreshes itself")
    void theDashboardCountsAndRefreshes() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("counters"))
                // Polling stands in for the SSE the design schedules for Phase 4 (section 13.6).
                .andExpect(content().string(containsString("data-refresh-seconds=\"30\"")))
                .andExpect(content().string(containsString("/js/auto-refresh.js")))
                // There is no NEW tile, because auto-dispatch leaves no complaint in NEW to count.
                .andExpect(content().string(containsString("Triage")))
                .andExpect(content().string(containsString("Resolved today")))
                .andExpect(content().string(containsString("Past due")));
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    @DisplayName("a signed-in manager lands on the dashboard at / — the route PR 3 left to this PR")
    void theRootRouteRenders() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(view().name("workflow/complaints"))
                .andExpect(model().attributeExists("complaints"));
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    @DisplayName("/complaints renders the same screen, with the Tailwind shell attached")
    void theComplaintListRenders() throws Exception {
        mockMvc.perform(get("/complaints"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/css/app.css")))
                .andExpect(content().string(containsString("All complaints")));
    }

    @Test
    @WithMockUser(roles = "ATTENDER")
    @DisplayName("an attender is never served the manager dashboard — section 11.3")
    void anAttenderIsRefusedTheDashboard() throws Exception {
        // Refused, and sent where they can work. The manager's screen is never rendered either
        // way; landing an attender on an error page for a screen they reached from a bookmark
        // would be a worse answer to the same refusal.
        mockMvc.perform(get("/")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/m"));
        mockMvc.perform(get("/complaints")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/m"));
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    @DisplayName("a manager uploads a CSV through the screen and the complaint appears in the list")
    void aManagerUploadsAndSeesTheComplaint() throws Exception {
        long before = complaints.count();

        mockMvc.perform(multipart("/complaints/upload").file(file("responses.csv", "text/csv", CSV)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/complaints"))
                .andExpect(flash().attributeExists("message"));

        assertThat(complaints.count()).isEqualTo(before + 1);

        mockMvc.perform(get("/complaints"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Water leaking from the ceiling fan")))
                .andExpect(content().string(containsString("1204")));
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    @DisplayName("a refused upload is a sentence on the screen, never a stack trace — section 11.6")
    void aRefusedUploadIsASentence() throws Exception {
        mockMvc.perform(
                        multipart("/complaints/upload")
                                .file(file("payload.pdf", "application/pdf", "%PDF-1.4"))
                                .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/complaints"))
                .andExpect(flash().attributeExists("error"));
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    @DisplayName("a run that failed on a renamed column tells the manager which column")
    void aFailedRunIsReportedOnTheScreen() throws Exception {
        mockMvc.perform(
                        multipart("/complaints/upload")
                                .file(file("responses.csv", "text/csv", "Timestamp,Room Number\n2026/02/14 11:00:00,214\n"))
                                .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", containsString("description")));
    }

    @Test
    @WithMockUser(roles = "ATTENDER")
    @DisplayName("an attender posting straight at the upload URL is refused — section 11.3")
    void anAttenderCannotPostAnUpload() throws Exception {
        mockMvc.perform(multipart("/complaints/upload").file(file("responses.csv", "text/csv", CSV)).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a manager assigns from the screen, then releases it back to the pool")
    void aManagerAssignsAndReleasesFromTheScreen() throws Exception {
        AppUser manager = users.saveAndFlush(Fixtures.manager());
        AppUser ravi = users.saveAndFlush(Fixtures.attender());
        long complaintId = complaints.saveAndFlush(Fixtures.complaintInTriage()).getId();
        // A real SignedInUser, not @WithMockUser: creating the task records who created it.
        TestingAuthenticationToken asManager = Fixtures.signIn(manager);
        SecurityContextHolder.clearContext();

        mockMvc.perform(
                        post("/complaints/{id}/assign", complaintId)
                                .param("attenderId", String.valueOf(ravi.getId()))
                                .with(authentication(asManager))
                                .with(csrf()))
                .andExpect(redirectedUrl("/complaints"));

        mockMvc.perform(get("/complaints").with(authentication(asManager)))
                .andExpect(content().string(containsString(ravi.getDisplayName())));

        mockMvc.perform(
                        post("/complaints/{id}/release", complaintId)
                                .with(authentication(asManager))
                                .with(csrf()))
                .andExpect(redirectedUrl("/complaints"));

        mockMvc.perform(get("/complaints").with(authentication(asManager)))
                .andExpect(content().string(containsString("In the pool")));
    }

    @Test
    @WithMockUser(roles = "ATTENDER")
    @DisplayName("an attender posting straight at the assign URL is refused — section 11.3")
    void anAttenderCannotAssign() throws Exception {
        mockMvc.perform(post("/complaints/{id}/assign", 1L).param("attenderId", "1").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    @DisplayName("the triage panel lists an unclassifiable complaint and offers the four domains")
    void theTriagePanelShowsWhatTheClassifierCouldNotPlace() throws Exception {
        mockMvc.perform(multipart("/complaints/upload").file(file("t.csv", "text/csv", AMBIGUOUS_CSV)).with(csrf()))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(get("/complaints"))
                .andExpect(content().string(containsString("Triage queue")))
                .andExpect(content().string(containsString("water coming from ceiling fan")))
                .andExpect(content().string(containsString("RO_PLANT")));
    }

    @Test
    @DisplayName("a manager sets the domain in one click and the complaint stops being unclassified")
    void aManagerSetsTheDomainInOneClick() throws Exception {
        AppUser manager = users.saveAndFlush(Fixtures.manager());
        long complaintId = complaints.saveAndFlush(Fixtures.complaintInTriage()).getId();
        TestingAuthenticationToken asManager = Fixtures.signIn(manager);
        SecurityContextHolder.clearContext();

        mockMvc.perform(
                        post("/complaints/{id}/domain", complaintId)
                                .param("domain", "NETWORK")
                                .with(authentication(asManager))
                                .with(csrf()))
                .andExpect(redirectedUrl("/complaints"))
                .andExpect(flash().attributeExists("message"));

        assertThat(complaints.findById(complaintId).orElseThrow().getDomain())
                .isEqualTo(ComplaintDomain.NETWORK);
    }

    @Test
    @WithMockUser(roles = "ATTENDER")
    @DisplayName("an attender can neither see the triage queue nor set a domain — section 11.3")
    void anAttenderCannotReachTriage() throws Exception {
        // The dashboard is where the panel lives, so being refused it is being refused the queue;
        // the route behind the buttons is refused on its own too, for a caller that skips the screen.
        mockMvc.perform(get("/complaints")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/m"));
        // The route behind the buttons stays a hard 403: a caller skipping the screen is not a
        // person who took a wrong turn, and turning that into a redirect would hide the attempt.
        mockMvc.perform(post("/complaints/{id}/domain", 1L).param("domain", "ELECTRICAL").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    @DisplayName("an upload without a CSRF token is refused — section 11.6")
    void csrfIsRequired() throws Exception {
        mockMvc.perform(multipart("/complaints/upload").file(file("responses.csv", "text/csv", CSV)))
                .andExpect(status().isForbidden());
    }
}
