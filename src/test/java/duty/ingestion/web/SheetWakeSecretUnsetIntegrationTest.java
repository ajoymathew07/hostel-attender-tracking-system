package duty.ingestion.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import duty.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * With no wake secret configured, the endpoint refuses every call, including one with an empty
 * header.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithAnonymousUser
class SheetWakeSecretUnsetIntegrationTest extends AbstractIntegrationTest {

    private static final String PATH = "/api/ingestion/wake";
    private static final String HEADER = "X-Wake-Secret";

    @Autowired private MockMvc mockMvc;

    @Test
    @DisplayName("with no secret configured the wake endpoint is closed, not open")
    void anUnconfiguredSecretClosesTheEndpoint() throws Exception {
        mockMvc.perform(post(PATH)).andExpect(status().isForbidden());
        // The empty header is the one that would slip through a naive equality check.
        mockMvc.perform(post(PATH).header(HEADER, "")).andExpect(status().isForbidden());
        mockMvc.perform(post(PATH).header(HEADER, "guess")).andExpect(status().isForbidden());
    }
}
