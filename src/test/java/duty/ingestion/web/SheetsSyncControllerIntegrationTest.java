package duty.ingestion.web;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import duty.ingestion.infrastructure.SheetReader;
import duty.ingestion.infrastructure.SheetsUnavailableException;
import duty.support.AbstractIntegrationTest;
import java.io.IOException;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * "Sync Now" at the HTTP edge (section 2.5, point 2).
 *
 * <p>Three things are worth asserting here and nothing else is: the button reaches the sheet, a
 * sheet that cannot be read becomes a sentence on the screen rather than a stack trace (section
 * 11.6), and an attender posting straight at the URL is refused (section 11.3). The
 * reconciliation behind it is tested where it lives.
 */
@SpringBootTest(
        properties = {
            "ingestion.sheets.spreadsheet-id=1SyntheticSheetIdForTheSyncButton",
            "ingestion.sheets.credentials=stubbed-in-tests",
            "ingestion.sheets.poll-interval=PT1H"
        })
@AutoConfigureMockMvc
class SheetsSyncControllerIntegrationTest extends AbstractIntegrationTest {

    @MockBean private SheetReader reader;

    @Autowired private MockMvc mockMvc;

    @Test
    @WithMockUser(roles = "MANAGER")
    @DisplayName("the manager presses Sync Now and the sheet's rows come back as counted complaints")
    void theManagerSyncsTheSheet() throws Exception {
        given(reader.readValues(anyString(), anyString()))
                .willReturn(
                        List.of(
                                List.of("Timestamp", "Describe the issue", "Room Number"),
                                List.of("2026/08/14 11:00:00", "Water leaking from the ceiling fan", "1204")));

        mockMvc.perform(post("/complaints/sync").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/complaints"))
                .andExpect(flash().attribute("message", Matchers.containsString("1 new complaint(s)")));
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    @DisplayName("a sheet that cannot be read is a sentence on the screen, never a stack trace")
    void anUnreachableSheetIsASentence() throws Exception {
        given(reader.readValues(anyString(), anyString()))
                .willThrow(new SheetsUnavailableException("1SyntheticSheetIdForTheSyncButton", new IOException("timeout")));

        mockMvc.perform(post("/complaints/sync").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", Matchers.containsString("could not be read")));
    }

    @Test
    @WithMockUser(roles = "ATTENDER")
    @DisplayName("an attender posting straight at the sync URL is refused — section 11.3")
    void anAttenderCannotSync() throws Exception {
        mockMvc.perform(post("/complaints/sync").with(csrf())).andExpect(status().isForbidden());
    }
}
