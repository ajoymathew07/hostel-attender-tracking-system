package duty.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import duty.audit.application.AuditTrail.AuditEntry;
import duty.audit.application.AuditTrail;
import duty.identity.infrastructure.AppUserRepository;
import duty.support.AbstractIntegrationTest;
import duty.support.Fixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The manager's SSE feed (section 9.5): it carries committed changes only, and only to the manager. */
@SpringBootTest
@AutoConfigureMockMvc
class ManagerStreamIntegrationTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private AuditTrail audit;
    @Autowired private AppUserRepository users;
    @Autowired private PlatformTransactionManager transactionManager;

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("a committed change reaches the manager's feed; one that rolled back never does")
    void onlyCommittedChangesAreSent() throws Exception {
        var asManager = Fixtures.signIn(users.saveAndFlush(Fixtures.manager()));
        MvcResult feed =
                mockMvc.perform(get("/api/manager/stream").with(authentication(asManager)))
                        .andExpect(request().asyncStarted())
                        .andReturn();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(
                rollback -> {
                    audit.record(AuditEntry.systemAction("complaint", 990L, "CHANGE_ROLLED_BACK", null));
                    rollback.setRollbackOnly();
                });
        transaction.executeWithoutResult(
                done -> audit.record(AuditEntry.systemAction("complaint", 991L, "CHANGE_COMMITTED", null)));

        assertThat(feed.getResponse().getContentAsString())
                .contains("\"event\":\"CHANGE_COMMITTED\"")
                .contains("\"id\":991")
                .doesNotContain("CHANGE_ROLLED_BACK");
    }

    @Test
    @DisplayName("an attender cannot open the manager's feed")
    void anAttenderIsRefused() throws Exception {
        var asAttender = Fixtures.signIn(users.saveAndFlush(Fixtures.attender()));
        mockMvc.perform(get("/api/manager/stream").with(authentication(asAttender)))
                .andExpect(status().isForbidden());
    }
}
