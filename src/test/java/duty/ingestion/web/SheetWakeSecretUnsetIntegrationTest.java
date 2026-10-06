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
 * No {@code INGESTION_WAKE_SECRET} — the state of every developer machine and every CI run.
 *
 * <p>This is the test that makes "unset means closed" a property rather than an intention. A guard
 * written the obvious way compares the presented header with the configured secret, and when both
 * are empty they match: the endpoint would be wide open on exactly the machines nobody is
 * watching, and the whole system's one unauthenticated URL would be an open door. It is its own
 * context because that is the only way to have the property genuinely absent.
 *
 * <p>Deliberately no {@code ingestion.sheets.*} either: an instance with no secret is refused
 * before anything asks whether there is a sheet.
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
