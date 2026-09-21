package in.ac.iitm.hostel.duty.identity.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.ac.iitm.hostel.duty.identity.domain.AppUser;
import in.ac.iitm.hostel.duty.identity.domain.UserRole;
import in.ac.iitm.hostel.duty.identity.infrastructure.AppUserRepository;
import in.ac.iitm.hostel.duty.support.AbstractIntegrationTest;
import in.ac.iitm.hostel.duty.support.Fixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The provisioning screen's actions (section 10.4, item 1.3 of section 13.3).
 *
 * <p>Every action is a POST, so every one of them carries a CSRF token (section 11.6), and every
 * one redirects afterwards — a refresh must not re-deactivate somebody.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(roles = "MANAGER")
class AttenderAdminControllerIntegrationTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private AppUserRepository users;

    private AppUser anAttender() {
        return users.saveAndFlush(Fixtures.attender());
    }

    @Test
    @DisplayName("deactivate then reactivate, through the screen")
    void deactivateAndReactivate() throws Exception {
        AppUser ravi = anAttender();

        mockMvc.perform(post("/admin/attenders/{id}/deactivate", ravi.getId()).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/attenders"))
                .andExpect(flash().attributeExists("message"));
        assertThat(users.findByEmailAndActiveIsTrue(ravi.getEmail())).isEmpty();

        mockMvc.perform(post("/admin/attenders/{id}/reactivate", ravi.getId()).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(users.findByEmailAndActiveIsTrue(ravi.getEmail())).isPresent();
    }

    @Test
    @DisplayName("a manager sets a role from the screen")
    void setARole() throws Exception {
        AppUser ravi = anAttender();

        mockMvc.perform(post("/admin/attenders/{id}/role", ravi.getId()).with(csrf()).param("role", "MANAGER"))
                .andExpect(status().is3xxRedirection());

        assertThat(users.findById(ravi.getId()).orElseThrow().getRole()).isEqualTo(UserRole.MANAGER);
    }

    @Test
    @DisplayName("a manager repoints the allowlist at a different Google address from the screen")
    void repointAnAddress() throws Exception {
        AppUser ravi = anAttender();
        String replacement = Fixtures.unique("repointed") + "@example.com";

        mockMvc.perform(post("/admin/attenders/{id}/email", ravi.getId()).with(csrf()).param("email", replacement))
                .andExpect(status().is3xxRedirection());

        assertThat(users.findByEmail(replacement)).isPresent();
    }

    @Test
    @DisplayName("a duplicate address comes back as a sentence, not a constraint violation")
    void aDuplicateAddressIsExplained() throws Exception {
        AppUser existing = anAttender();

        mockMvc.perform(
                        post("/admin/attenders")
                                .with(csrf())
                                .param("displayName", "Impostor")
                                .param("email", existing.getEmail().toUpperCase())
                                .param("role", "ATTENDER"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/attenders"))
                .andExpect(flash().attributeExists("error"));
    }

    @Test
    @DisplayName("acting on a user who no longer exists refreshes the screen instead of throwing")
    void aStaleScreenIsHandled() throws Exception {
        mockMvc.perform(post("/admin/attenders/{id}/deactivate", -1L).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/attenders"))
                .andExpect(flash().attributeExists("error"));
    }
}
