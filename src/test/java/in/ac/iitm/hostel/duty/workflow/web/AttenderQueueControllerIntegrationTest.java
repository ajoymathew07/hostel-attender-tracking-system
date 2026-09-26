package in.ac.iitm.hostel.duty.workflow.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import in.ac.iitm.hostel.duty.identity.domain.AppUser;
import in.ac.iitm.hostel.duty.identity.infrastructure.AppUserRepository;
import in.ac.iitm.hostel.duty.support.AbstractIntegrationTest;
import in.ac.iitm.hostel.duty.support.Fixtures;
import in.ac.iitm.hostel.duty.workflow.application.ManualAssignmentService;
import in.ac.iitm.hostel.duty.workflow.domain.Complaint;
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
 * The attender's phone at the HTTP edge (sections 10.2, 10.4).
 *
 * <p>The ownership rule is proved at the service layer in {@code AttenderQueueIntegrationTest};
 * what this class adds is that the two routes exist, render, and that the refusal reaches the
 * browser as a 403 rather than a rendered page belonging to someone else.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AttenderQueueControllerIntegrationTest extends AbstractIntegrationTest {

    private static final String NAME = "Synthetic Resident";
    private static final String ROLL_NO = "CE21B452";
    private static final String PHONE = "9000000452";

    @Autowired private MockMvc mockMvc;
    @Autowired private ManualAssignmentService assignment;
    @Autowired private ComplaintRepository complaints;
    @Autowired private TaskRepository tasks;
    @Autowired private AppUserRepository users;

    private TestingAuthenticationToken asRavi;
    private TestingAuthenticationToken asKumar;
    private long taskId;

    @BeforeEach
    void assignSomeWork() {
        AppUser manager = users.saveAndFlush(Fixtures.manager());
        AppUser ravi = users.saveAndFlush(Fixtures.attender());
        AppUser kumar = users.saveAndFlush(Fixtures.attender());

        Complaint complaint = Fixtures.complaint();
        complaint.recordReporter(NAME, ROLL_NO, PHONE, "resident@example.com", "0214");
        long complaintId = complaints.saveAndFlush(complaint).getId();

        Fixtures.signIn(manager);
        assignment.assign(complaintId, ravi.getId());
        taskId = tasks.findByComplaintId(complaintId).orElseThrow().getId();

        // The tokens are built here and handed to MockMvc per request; the context itself is left
        // empty so that nothing passes because of who happened to be signed in during setup.
        asRavi = Fixtures.signIn(ravi);
        asKumar = Fixtures.signIn(kumar);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("/m renders the attender's own queue with the mobile shell")
    void theQueueRenders() throws Exception {
        mockMvc.perform(get("/m").with(authentication(asRavi)))
                .andExpect(status().isOk())
                .andExpect(view().name("workflow/queue"))
                .andExpect(content().string(containsString("/css/app.css")))
                .andExpect(content().string(containsString("Open task")))
                .andExpect(content().string(containsString(PHONE)));
    }

    @Test
    @DisplayName("another attender's queue is empty, not a peek at this one — E25 is an empty state")
    void anotherAttendersQueueIsEmpty() throws Exception {
        mockMvc.perform(get("/m").with(authentication(asKumar)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Nothing assigned to you")))
                .andExpect(content().string(not(containsString(PHONE))));
    }

    @Test
    @DisplayName("/m/tasks/{id} renders for the assignee — the push notification's deep link")
    void theTaskDetailRendersForTheAssignee() throws Exception {
        mockMvc.perform(get("/m/tasks/" + taskId).with(authentication(asRavi)))
                .andExpect(status().isOk())
                .andExpect(view().name("workflow/task"))
                .andExpect(content().string(containsString(NAME)))
                .andExpect(content().string(containsString("tel:" + PHONE)));
    }

    @Test
    @DisplayName("/m/tasks/{id} is 403 for an attender who does not hold it — section 11.3")
    void theTaskDetailIsRefusedToEveryoneElse() throws Exception {
        mockMvc.perform(get("/m/tasks/" + taskId).with(authentication(asKumar)))
                .andExpect(status().isForbidden());
    }
}
