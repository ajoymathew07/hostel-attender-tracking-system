package duty.settings.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import duty.dispatch.application.DispatchSettings;
import duty.settings.infrastructure.SettingRepository;
import duty.support.AbstractIntegrationTest;
import duty.workflow.application.SlaSettings;
import java.time.LocalTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/** The {@code /settings} screen at the HTTP edge (section 10.4). */
@SpringBootTest
@AutoConfigureMockMvc
class SettingsControllerIntegrationTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private SettingRepository settings;
    @Autowired private SlaSettings sla;
    @Autowired private DispatchSettings dispatch;

    @AfterEach
    void reset() {
        settings.deleteAll();
        settings.flush();
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    @DisplayName("the screen renders with the values currently in force")
    void theScreenShowsWhatIsInForce() throws Exception {
        mockMvc.perform(get("/settings"))
                .andExpect(status().isOk())
                .andExpect(view().name("settings/settings"))
                .andExpect(content().string(containsString("Who gets the work")))
                .andExpect(content().string(containsString("What counts as late")));
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    @DisplayName("a saved screenful reaches the behaviour, SLA hours and all")
    void savingChangesWhatTheSystemDoes() throws Exception {
        mockMvc.perform(
                        post("/settings")
                                .with(csrf())
                                .param("floorWeight", "0.8")
                                .param("loadWeight", "0.2")
                                .param("loadCap", "5")
                                .param("slaHours[ELECTRICAL.P1]", "2.5")
                                .param("dayStart", "07:00")
                                .param("dayEnd", "21:30"))
                .andExpect(redirectedUrl("/settings"))
                .andExpect(flash().attributeExists("message"));

        assertThat(dispatch.current().floor()).isEqualTo(0.8);
        assertThat(dispatch.current().loadCap()).isEqualTo(5);
        assertThat(sla.configuration().cells())
                .describedAs("2.5 hours typed on the screen is PT2H30M in the table")
                .containsEntry("ELECTRICAL.P1", 2.5);
        assertThat(sla.configuration().dayEnd()).isEqualTo(LocalTime.of(21, 30));
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    @DisplayName("one refused field saves nothing at all, not the fields that came before it")
    void aRefusedFieldLeavesEverythingAlone() throws Exception {
        mockMvc.perform(
                        post("/settings")
                                .with(csrf())
                                .param("floorWeight", "0.9")
                                .param("loadWeight", "0.1")
                                .param("loadCap", "5")
                                .param("dayStart", "21:00")
                                .param("dayEnd", "08:00"))
                .andExpect(redirectedUrl("/settings"))
                .andExpect(flash().attributeExists("error"));

        assertThat(dispatch.current().floor())
                .describedAs("the working day was refused, so the weights never moved either")
                .isEqualTo(0.60);
        assertThat(settings.count()).isZero();
    }

    @Test
    @WithMockUser(roles = "ATTENDER")
    @DisplayName("an attender cannot open it, let alone save it")
    void theScreenIsTheManagersAlone() throws Exception {
        mockMvc.perform(get("/settings")).andExpect(status().isForbidden());
    }
}
