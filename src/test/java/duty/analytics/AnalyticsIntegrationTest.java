package duty.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsString;

import duty.analytics.application.AnalyticsService.AlertView;
import duty.analytics.application.AnalyticsService.DomainSummary;
import duty.analytics.application.AnalyticsService;
import duty.identity.infrastructure.AppUserRepository;
import duty.support.AbstractIntegrationTest;
import duty.support.Fixtures;
import duty.workflow.infrastructure.ComplaintRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Resolution time, the bottleneck and the A1/A2 detectors over real rows (section 8). Complaints and
 * their audited transitions are written directly, so each test controls every timestamp.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AnalyticsIntegrationTest extends AbstractIntegrationTest {

    @Autowired private AnalyticsService analytics;
    @Autowired private ComplaintRepository complaints;
    @Autowired private AppUserRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mockMvc;

    @BeforeEach
    void signInAsManager() {
        Fixtures.signIn(users.saveAndFlush(Fixtures.manager()));
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    /** A complaint reported at {@code reportedAt}, moved through the given states one gap at a time. */
    private String complaint(String domain, String room, Instant reportedAt, Object... stateThenHours) {
        long id = complaints.saveAndFlush(Fixtures.complaint()).getId();
        jdbc.update(
                "UPDATE complaint SET domain = ?, reporter_room = ?, reported_at = ? WHERE id = ?",
                domain,
                room,
                LocalDateTime.ofInstant(reportedAt, ZoneOffset.UTC),
                id);
        String from = "NEW";
        Instant at = reportedAt;
        for (int i = 0; i < stateThenHours.length; i += 2) {
            String to = (String) stateThenHours[i];
            at = at.plus(Duration.ofMinutes((long) (((Number) stateThenHours[i + 1]).doubleValue() * 60)));
            jdbc.update(
                    "INSERT INTO audit_event (occurred_at, entity_type, entity_id, event_type, from_state, to_state)"
                            + " VALUES (?, 'complaint', ?, 'TEST_MOVE', ?, ?)",
                    LocalDateTime.ofInstant(at, ZoneOffset.UTC),
                    id,
                    from,
                    to);
            from = to;
        }
        return complaints.findById(id).orElseThrow().getPublicRef();
    }

    /** Resolved in {@code hours} of working time: a slow filing stage and a long hold that must not count. */
    private void resolvedIn(String domain, Instant reportedAt, double filingHours) {
        complaint(
                domain, null, reportedAt,
                "ASSIGNED", 1, "INSPECTING", 1, "AWAITING_CMS", 1, "IN_PROGRESS", filingHours,
                "ON_HOLD", 1, "IN_PROGRESS", 50, "RESOLVED", 1);
    }

    @Test
    @DisplayName("resolution time leaves hold out, and a stage with most of the time is named the bottleneck")
    void resolutionTimeAndBottleneck() {
        Instant from = Instant.parse("2020-03-01T00:00:00Z");
        for (int i = 0; i < 10; i++) {
            resolvedIn("CIVIL", from.plus(Duration.ofHours(i)), 10);
        }

        List<DomainSummary> rows = analytics.byDomain(from, from.plus(Duration.ofDays(1)));

        assertThat(rows).singleElement().satisfies(
                civil -> {
                    assertThat(civil.domain()).isEqualTo("CIVIL");
                    assertThat(civil.n()).isEqualTo(10);
                    assertThat(civil.median()).describedAs("1+1+1+10+1+1, the 50 h hold left out").isEqualTo(15.0);
                    assertThat(civil.bottleneckStage()).isEqualTo("Filing");
                    assertThat(civil.bottleneckShare()).isCloseTo(10 / 15.0, within(1e-9));
                });
    }

    @Test
    @DisplayName("A2: three complaints from one room in a fortnight raise one HIGH alert, however often the scan runs")
    void chronicRoom() {
        String room = Fixtures.unique("9");
        Instant now = Instant.now();
        for (int day = 1; day <= 3; day++) {
            complaint("ELECTRICAL", room, now.minus(Duration.ofDays(day)));
        }

        analytics.scan();
        analytics.scan();

        List<AlertView> found = alertsAbout("ROOM " + room);
        assertThat(found).singleElement().satisfies(
                alert -> {
                    assertThat(alert.detector()).isEqualTo("A2");
                    assertThat(alert.severity()).isEqualTo("HIGH");
                    assertThat(alert.evidence()).contains("\"in14Days\":3");
                });

        analytics.acknowledge(found.get(0).id());
        assertThat(alertsAbout("ROOM " + room)).isEmpty();
    }

    @Test
    @DisplayName("A1: a complaint resolved far slower than its domain's other twelve is flagged with its evidence")
    void prolongedResolution() {
        Instant start = Instant.now().minus(Duration.ofDays(20));
        for (int i = 0; i < 12; i++) {
            resolvedIn("NETWORK", start.plus(Duration.ofHours(i)), 8 + i % 3);
        }
        String slow = complaint("NETWORK", null, start, "ASSIGNED", 1, "INSPECTING", 1, "AWAITING_CMS", 1, "IN_PROGRESS", 300, "RESOLVED", 1);

        analytics.scan();

        assertThat(alertsAbout("COMPLAINT " + slow)).singleElement().satisfies(
                alert -> {
                    assertThat(alert.detector()).isEqualTo("A1");
                    assertThat(alert.evidence()).contains("\"threshold\":3.5");
                });
    }

    @Test
    @DisplayName("the analytics page is the manager's; an attender is refused")
    void thePageIsTheManagers() throws Exception {
        var asManager = Fixtures.signIn(users.saveAndFlush(Fixtures.manager()));
        mockMvc.perform(get("/analytics").with(authentication(asManager)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Resolution time by domain")))
                .andExpect(content().string(containsString("/api/manager/reports/weekly.xlsx")));

        var asAttender = Fixtures.signIn(users.saveAndFlush(Fixtures.attender()));
        mockMvc.perform(get("/analytics").with(authentication(asAttender))).andExpect(status().isForbidden());
    }

    private List<AlertView> alertsAbout(String subject) {
        return analytics.openAlerts().stream().filter(alert -> alert.subject().equals(subject)).toList();
    }
}
