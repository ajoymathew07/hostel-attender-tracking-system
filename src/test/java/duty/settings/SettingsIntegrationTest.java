package duty.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import duty.dispatch.application.DispatchSettings;
import duty.identity.domain.AppUser;
import duty.identity.infrastructure.AppUserRepository;
import duty.settings.application.SettingsStore;
import duty.settings.infrastructure.SettingRepository;
import duty.support.AbstractIntegrationTest;
import duty.support.Fixtures;
import duty.workflow.application.SlaSettings;
import java.time.LocalTime;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * A saved setting changes behaviour immediately: new weights and SLA windows apply to the next
 * complaint without a restart.
 */
@SpringBootTest
class SettingsIntegrationTest extends AbstractIntegrationTest {

    @Autowired private SettingsStore store;
    @Autowired private SettingRepository settings;
    @Autowired private DispatchSettings dispatch;
    @Autowired private SlaSettings sla;
    @Autowired private AppUserRepository users;

    private AppUser manager;

    @BeforeEach
    void signIn() {
        manager = users.saveAndFlush(Fixtures.manager());
        Fixtures.signIn(manager);
        settings.deleteAll();
        settings.flush();
    }

    @AfterEach
    void signOut() {
        settings.deleteAll();
        settings.flush();
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("with nothing overridden, every value is exactly what application.yml says")
    void theYamlIsTheDefault() {
        assertThat(dispatch.current().floor()).isEqualTo(0.60);
        assertThat(dispatch.current().load()).isEqualTo(0.40);
        assertThat(dispatch.current().loadCap()).isEqualTo(8);
        assertThat(sla.configuration().dayStart()).isEqualTo(LocalTime.of(8, 0));
        assertThat(sla.configuration().cells()).containsEntry("ELECTRICAL.P1", 4.0);
    }

    @Test
    @DisplayName("a saved weight is what the next dispatch scores with — no restart")
    void aSavedWeightTakesEffectImmediately() {
        store.save(dispatch.edits(0.20, 0.80, 4));

        assertThat(dispatch.current().floor()).isEqualTo(0.20);
        assertThat(dispatch.current().loadCap()).isEqualTo(4);
    }

    @Test
    @DisplayName("a saved SLA window is what the clock measures against")
    void aSavedWindowTakesEffectImmediately() {
        store.save(
                sla.edits(Map.of("ELECTRICAL.P1", 2.0), LocalTime.of(7, 0), LocalTime.of(21, 0)));

        assertThat(sla.configuration().cells()).containsEntry("ELECTRICAL.P1", 2.0);
        assertThat(sla.configuration().dayStart()).isEqualTo(LocalTime.of(7, 0));
        assertThat(sla.configuration().cells())
                .describedAs("a cell nobody touched still follows the YAML")
                .containsEntry("CIVIL.P1", 8.0);
    }

    @Test
    @DisplayName("weights that do not sum to 1 are refused, not quietly rescaled")
    void weightsMustSumToOne() {
        assertThatThrownBy(() -> dispatch.edits(0.70, 0.40, 8))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sum to 1");
    }

    @Test
    @DisplayName("a working day that ends before it starts is refused")
    void theWorkingDayMustBeADay() {
        assertThatThrownBy(() -> sla.edits(Map.of(), LocalTime.of(20, 0), LocalTime.of(8, 0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an SLA window of zero is refused — everything would arrive already breached")
    void anSlaWindowMustBePositive() {
        assertThatThrownBy(
                        () -> sla.edits(Map.of("CIVIL.P2", 0.0), LocalTime.of(8, 0), LocalTime.of(20, 0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a change is audited with what it was and what it became")
    void everyChangeIsAudited() {
        store.save(dispatch.edits(0.20, 0.80, 4));
        store.save(dispatch.edits(0.30, 0.70, 4));

        assertThat(settings.findByName(DispatchSettings.FLOOR_WEIGHT))
                .get()
                .satisfies(
                        row -> {
                            assertThat(row.getValue()).isEqualTo("0.3");
                            assertThat(row.getUpdatedBy()).isEqualTo(manager.getId());
                        });
    }

    @Test
    @DisplayName("writing is the manager's; an attender is refused at the service, not the screen")
    void onlyAManagerMaySave() {
        Fixtures.signIn(users.saveAndFlush(Fixtures.attender()));

        assertThatThrownBy(() -> store.save(Map.of(DispatchSettings.LOAD_CAP, "2")))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("reading is not: the dispatcher reads these during an unauthenticated poll")
    void readingNeedsNobody() {
        SecurityContextHolder.clearContext();

        assertThat(store.current().values()).isEmpty();
        assertThat(dispatch.current().loadCap()).isEqualTo(8);
    }
}
