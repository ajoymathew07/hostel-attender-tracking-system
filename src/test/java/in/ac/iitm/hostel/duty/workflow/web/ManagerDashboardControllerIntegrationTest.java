package in.ac.iitm.hostel.duty.workflow.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import in.ac.iitm.hostel.duty.support.AbstractIntegrationTest;
import in.ac.iitm.hostel.duty.workflow.infrastructure.ComplaintRepository;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
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

    @Autowired private MockMvc mockMvc;
    @Autowired private ComplaintRepository complaints;

    private static MockMultipartFile file(String name, String contentType, String body) {
        return new MockMultipartFile("file", name, contentType, body.getBytes(StandardCharsets.UTF_8));
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
    @DisplayName("an attender is refused the manager dashboard — section 11.3")
    void anAttenderIsRefusedTheDashboard() throws Exception {
        mockMvc.perform(get("/")).andExpect(status().isForbidden());
        mockMvc.perform(get("/complaints")).andExpect(status().isForbidden());
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
    @WithMockUser(roles = "MANAGER")
    @DisplayName("an upload without a CSRF token is refused — section 11.6")
    void csrfIsRequired() throws Exception {
        mockMvc.perform(multipart("/complaints/upload").file(file("responses.csv", "text/csv", CSV)))
                .andExpect(status().isForbidden());
    }
}
