package duty.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import duty.dispatch.domain.AvailabilityStatus;
import duty.dispatch.domain.AvailabilityWindow;
import duty.dispatch.domain.Coverage;
import duty.dispatch.domain.Floor;
import duty.dispatch.domain.FloorAssignment;
import duty.dispatch.infrastructure.AvailabilityWindowRepository;
import duty.dispatch.infrastructure.FloorAssignmentRepository;
import duty.dispatch.infrastructure.FloorRepository;
import duty.identity.infrastructure.AppUserRepository;
import duty.support.AbstractIntegrationTest;
import duty.support.Fixtures;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Floors, coverage and availability windows: uniqueness rules and which window is in force at a given
 * moment.
 */
@SpringBootTest
class CoverageAndAvailabilityIntegrationTest extends AbstractIntegrationTest {

    @Autowired private FloorRepository floors;
    @Autowired private FloorAssignmentRepository coverage;
    @Autowired private AvailabilityWindowRepository availability;
    @Autowired private AppUserRepository users;

    private Long attenderId;
    private Long managerId;

    @BeforeEach
    void provision() {
        this.attenderId = users.saveAndFlush(Fixtures.attender()).getId();
        this.managerId = users.saveAndFlush(Fixtures.manager()).getId();
    }

    @Test
    @DisplayName("uq_floor: a wing and floor number identify one floor")
    void floorIdentityIsWingAndNumber() {
        String wing = Fixtures.unique("W");
        Floor ground = floors.saveAndFlush(new Floor(wing, 0, "Ground"));

        assertThatThrownBy(() -> floors.saveAndFlush(new Floor(wing, 0, "Ground again")))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(floors.findByWingAndFloorNumber(wing, 0))
                .get()
                .extracting(Floor::getId)
                .isEqualTo(ground.getId());

        // The same number in another wing is a different floor.
        assertThat(floors.saveAndFlush(new Floor(Fixtures.unique("W"), 0, "Ground")).getId())
                .isNotNull();
    }

    @Test
    @DisplayName("a floor can be relabelled without changing its identity")
    void floorCanBeRelabelled() {
        Floor floor = floors.saveAndFlush(new Floor(Fixtures.unique("W"), 2, "Second"));
        floor.relabel("Second (mess side)");
        floors.saveAndFlush(floor);

        Floor reloaded = floors.findById(floor.getId()).orElseThrow();
        assertThat(reloaded.getLabel()).isEqualTo("Second (mess side)");
        assertThat(reloaded.getFloorNumber()).isEqualTo(2);
        assertThat(reloaded.getWing()).isEqualTo(floor.getWing());
    }

    @Test
    @DisplayName("uq_fa: an attender covers a floor once, and coverage can be upgraded in place")
    void coverageIsUniquePerAttenderAndFloor() {
        Floor floor = floors.saveAndFlush(new Floor(Fixtures.unique("W"), 1, "First"));

        FloorAssignment assignment =
                coverage.saveAndFlush(new FloorAssignment(attenderId, floor, Coverage.SECONDARY));
        assertThat(assignment.isPrimary()).isFalse();
        assertThat(assignment.getVersion()).isEqualTo(0L);

        assertThatThrownBy(
                        () ->
                                coverage.saveAndFlush(
                                        new FloorAssignment(attenderId, floor, Coverage.PRIMARY)))
                .describedAs("a second coverage row for the same pair would make dispatch ambiguous")
                .isInstanceOf(DataIntegrityViolationException.class);

        assignment.changeCoverage(Coverage.PRIMARY);
        coverage.saveAndFlush(assignment);

        FloorAssignment reloaded = coverage.findById(assignment.getId()).orElseThrow();
        assertThat(reloaded.isPrimary()).isTrue();
        assertThat(reloaded.getCoverage()).isEqualTo(Coverage.PRIMARY);
        assertThat(reloaded.getAttenderId()).isEqualTo(attenderId);
        assertThat(reloaded.getFloor().getId()).isEqualTo(floor.getId());
        assertThat(reloaded.getVersion()).isEqualTo(1L);
    }

    @Test
    @DisplayName("coverage is queryable by floor and by attender — the dispatch lookups")
    void coverageIsQueryableBothWays() {
        Floor floor = floors.saveAndFlush(new Floor(Fixtures.unique("W"), 3, "Third"));
        coverage.saveAndFlush(new FloorAssignment(attenderId, floor, Coverage.PRIMARY));

        assertThat(coverage.findByFloorId(floor.getId())).hasSize(1);
        assertThat(coverage.findByFloorIdAndCoverage(floor.getId(), Coverage.PRIMARY)).hasSize(1);
        assertThat(coverage.findByFloorIdAndCoverage(floor.getId(), Coverage.SECONDARY)).isEmpty();
        assertThat(coverage.findByAttenderId(attenderId)).hasSize(1);
        assertThat(coverage.findByAttenderIdAndFloorId(attenderId, floor.getId())).isPresent();
    }

    @Test
    @DisplayName("an availability window is in force between its bounds, end exclusive")
    void windowCoversTheRightInstants() {
        AvailabilityWindow leave =
                new AvailabilityWindow(
                        attenderId,
                        AvailabilityStatus.ON_LEAVE,
                        Instant.parse("2026-03-01T00:00:00Z"),
                        Instant.parse("2026-03-05T00:00:00Z"),
                        "family function",
                        managerId);

        assertThat(leave.coversInstant(Instant.parse("2026-02-28T23:59:59Z"))).isFalse();
        assertThat(leave.coversInstant(Instant.parse("2026-03-01T00:00:00Z"))).isTrue();
        assertThat(leave.coversInstant(Instant.parse("2026-03-04T23:59:59Z"))).isTrue();
        assertThat(leave.coversInstant(Instant.parse("2026-03-05T00:00:00Z"))).isFalse();

        availability.saveAndFlush(leave);
        AvailabilityWindow reloaded = availability.findById(leave.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AvailabilityStatus.ON_LEAVE);
        assertThat(reloaded.getReason()).isEqualTo("family function");
        assertThat(reloaded.getRecordedBy()).isEqualTo(managerId);
        assertThat(reloaded.getStartsAt()).isEqualTo(Instant.parse("2026-03-01T00:00:00Z"));
        assertThat(reloaded.getEndsAt()).isEqualTo(Instant.parse("2026-03-05T00:00:00Z"));
        assertThat(reloaded.getAttenderId()).isEqualTo(attenderId);
    }

    @Test
    @DisplayName("an open-ended window applies from its start onward, and can be closed early")
    void openEndedWindowCanBeClosed() {
        AvailabilityWindow offDuty =
                availability.saveAndFlush(
                        new AvailabilityWindow(
                                attenderId,
                                AvailabilityStatus.OFF_DUTY,
                                Instant.parse("2026-03-01T00:00:00Z"),
                                null,
                                null,
                                managerId));

        assertThat(offDuty.getEndsAt()).isNull();
        assertThat(offDuty.coversInstant(Instant.parse("2030-01-01T00:00:00Z"))).isTrue();

        offDuty.closeAt(Instant.parse("2026-03-02T00:00:00Z"));
        availability.saveAndFlush(offDuty);

        assertThat(availability.findById(offDuty.getId()).orElseThrow().getEndsAt())
                .isEqualTo(Instant.parse("2026-03-02T00:00:00Z"));
        assertThat(availability.findInForce(attenderId, Instant.parse("2026-03-01T12:00:00Z")))
                .hasSize(1);
        assertThat(availability.findInForce(attenderId, Instant.parse("2026-03-03T12:00:00Z")))
                .isEmpty();
        assertThat(availability.findByAttenderIdOrderByStartsAtDesc(attenderId)).hasSize(1);
    }

    @Test
    @DisplayName("a window cannot end before it starts")
    void windowBoundsAreChecked() {
        assertThatThrownBy(
                        () ->
                                new AvailabilityWindow(
                                        attenderId,
                                        AvailabilityStatus.ON_LEAVE,
                                        Instant.parse("2026-03-05T00:00:00Z"),
                                        Instant.parse("2026-03-01T00:00:00Z"),
                                        null,
                                        managerId))
                .isInstanceOf(IllegalArgumentException.class);

        AvailabilityWindow window =
                new AvailabilityWindow(
                        attenderId,
                        AvailabilityStatus.AVAILABLE,
                        Instant.parse("2026-03-05T00:00:00Z"),
                        null,
                        null,
                        managerId);
        assertThatThrownBy(() -> window.closeAt(Instant.parse("2026-03-01T00:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("only AVAILABLE is eligible for dispatch — E6 keeps nothing lost")
    void onlyAvailableIsDispatchable() {
        assertThat(AvailabilityStatus.AVAILABLE.eligibleForDispatch()).isTrue();
        assertThat(AvailabilityStatus.ON_LEAVE.eligibleForDispatch()).isFalse();
        assertThat(AvailabilityStatus.OFF_DUTY.eligibleForDispatch()).isFalse();
    }
}
