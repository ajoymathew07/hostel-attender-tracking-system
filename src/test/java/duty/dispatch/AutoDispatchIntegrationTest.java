package duty.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import duty.dispatch.domain.AvailabilityStatus;
import duty.dispatch.domain.AvailabilityWindow;
import duty.dispatch.domain.Coverage;
import duty.dispatch.domain.Floor;
import duty.dispatch.domain.FloorAssignment;
import duty.dispatch.infrastructure.AvailabilityWindowRepository;
import duty.dispatch.infrastructure.FloorAssignmentRepository;
import duty.dispatch.infrastructure.FloorRepository;
import duty.identity.domain.AppUser;
import duty.identity.domain.UserRole;
import duty.identity.infrastructure.AppUserRepository;
import duty.support.AbstractIntegrationTest;
import duty.support.Fixtures;
import duty.workflow.application.ComplaintIntake;
import duty.workflow.application.NewComplaint;
import duty.workflow.domain.AssignmentStatus;
import duty.workflow.domain.AssignMethod;
import duty.workflow.domain.Complaint;
import duty.workflow.domain.ComplaintStatus;
import duty.workflow.domain.TaskAssignment;
import duty.workflow.infrastructure.ComplaintRepository;
import duty.workflow.infrastructure.TaskAssignmentRepository;
import duty.workflow.infrastructure.TaskRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Auto-dispatch end to end: every new complaint ends up assigned, or in triage with a reason.
 *
 * <p>Each test first deactivates attenders left by other test classes, so it scores only the roster it
 * set up.
 */
@SpringBootTest
class AutoDispatchIntegrationTest extends AbstractIntegrationTest {

    @Autowired private ComplaintIntake intake;
    @Autowired private ComplaintRepository complaints;
    @Autowired private TaskRepository tasks;
    @Autowired private TaskAssignmentRepository assignments;
    @Autowired private AppUserRepository users;
    @Autowired private FloorRepository floors;
    @Autowired private FloorAssignmentRepository coverage;
    @Autowired private AvailabilityWindowRepository availability;

    private AppUser ravi;
    private AppUser kumar;
    private Long groundFloor;
    private Long secondFloor;

    @BeforeEach
    void roster() {
        List<AppUser> leftOver = users.findByRoleAndActiveIsTrueOrderByDisplayNameAsc(UserRole.ATTENDER);
        leftOver.forEach(AppUser::deactivate);
        users.saveAllAndFlush(leftOver);

        ravi = users.saveAndFlush(Fixtures.attender());
        kumar = users.saveAndFlush(Fixtures.attender());

        // The seeded Cauvery floors, which V12 put there: dispatch has nothing to score without
        // them, and the migration is as much a part of this feature as the code is.
        groundFloor = floors.findByWingAndFloorNumber("CAUVERY", 1).orElseThrow().getId();
        secondFloor = floors.findByWingAndFloorNumber("CAUVERY", 3).orElseThrow().getId();
    }

    private void covers(AppUser attender, Long floorId, Coverage how) {
        coverage.saveAndFlush(
                new FloorAssignment(attender.getId(), floors.findById(floorId).orElseThrow(), how));
    }

    private void onLeave(AppUser attender) {
        availability.saveAndFlush(
                new AvailabilityWindow(
                        attender.getId(),
                        AvailabilityStatus.ON_LEAVE,
                        Instant.parse("2020-01-01T00:00:00Z"),
                        null,
                        "leave",
                        attender.getId()));
    }

    private Complaint arrive(String text, String room) {
        return arrive(text, room, "Electrical");
    }

    private Complaint arrive(String text, String room, String reportedDomain) {
        long id =
                intake.record(
                                new NewComplaint(
                                        text,
                                        Instant.parse("2026-02-01T05:30:00Z"),
                                        "Resident",
                                        "CS26B001",
                                        "9000000000",
                                        "resident@example.com",
                                        room,
                                        reportedDomain,
                                        null))
                        .id();
        return complaints.findById(id).orElseThrow();
    }

    private Optional<TaskAssignment> holderOf(Complaint complaint) {
        return tasks.findByComplaintId(complaint.getId())
                .map(task -> assignments.findByTaskIdAndStatus(task.getId(), AssignmentStatus.ACTIVE))
                .orElseGet(List::of)
                .stream()
                .findFirst();
    }

    @Test
    @DisplayName("a classified complaint reaches the attender who covers its floor — T2")
    void theFloorPrimaryGetsTheWork() {
        covers(ravi, groundFloor, Coverage.PRIMARY);
        covers(kumar, secondFloor, Coverage.PRIMARY);

        Complaint complaint = arrive("tube light not working in my room", "169");

        assertThat(complaint.getStatus()).isEqualTo(ComplaintStatus.ASSIGNED);
        assertThat(complaint.getFloorId()).isEqualTo(groundFloor);
        assertThat(holderOf(complaint))
                .get()
                .satisfies(
                        held -> {
                            assertThat(held.getAttenderId()).isEqualTo(ravi.getId());
                            assertThat(held.getAssignMethod()).isEqualTo(AssignMethod.AUTO);
                            assertThat(held.getAssignedBy())
                                    .describedAs("nobody assigned it; the heuristic did")
                                    .isNull();
                        });
    }

    @Test
    @DisplayName("the snapshot says why them, and what they beat — R3")
    void theScoreSnapshotExplainsTheChoice() {
        covers(ravi, groundFloor, Coverage.PRIMARY);
        covers(kumar, groundFloor, Coverage.SECONDARY);

        Complaint complaint = arrive("fan is making a loud noise", "104");

        assertThat(holderOf(complaint))
                .get()
                .extracting(TaskAssignment::getScoreSnapshot)
                .asString()
                .contains("\"chosen\":" + ravi.getId())
                .contains("\"components\"")
                .contains("\"weights\"")
                .contains("\"runnersUp\"")
                .contains(String.valueOf(kumar.getId()));
    }

    @Test
    @DisplayName("the attender on leave is skipped, however well they would have scored — E6")
    void leaveIsAGateAndNotAScore() {
        covers(ravi, groundFloor, Coverage.PRIMARY);
        covers(kumar, secondFloor, Coverage.SECONDARY);
        onLeave(ravi);

        Complaint complaint = arrive("switch board sparking", "102");

        assertThat(complaint.getStatus()).isEqualTo(ComplaintStatus.ASSIGNED);
        assertThat(holderOf(complaint)).get().extracting(TaskAssignment::getAttenderId).isEqualTo(kumar.getId());
    }

    @Test
    @DisplayName("nobody available at all sends it to triage, with the reason — T3, E = empty")
    void noAvailableAttenderGoesToTriage() {
        covers(ravi, groundFloor, Coverage.PRIMARY);
        onLeave(ravi);
        onLeave(kumar);

        Complaint complaint = arrive("no power in the corridor", "108");

        assertThat(complaint.getStatus()).isEqualTo(ComplaintStatus.TRIAGE);
        assertThat(tasks.findByComplaintId(complaint.getId()))
                .describedAs("nothing was assigned, so there is nothing to hold")
                .isEmpty();
    }

    @Test
    @DisplayName("an unreadable room number goes to triage rather than being guessed at — E5")
    void anUnmappedRoomGoesToTriage() {
        covers(ravi, groundFloor, Coverage.PRIMARY);

        Complaint complaint = arrive("water leaking from the ceiling", "near the mess");

        assertThat(complaint.getFloorId()).isNull();
        assertThat(complaint.getStatus()).isEqualTo(ComplaintStatus.TRIAGE);
    }

    @Test
    @DisplayName("what the resident could not place never reaches the heuristic — T3")
    void anUnclassifiedComplaintIsTriagedBeforeDispatch() {
        covers(ravi, groundFloor, Coverage.PRIMARY);

        Complaint complaint = arrive("something is wrong in my room", "169", "Others");

        assertThat(complaint.getStatus()).isEqualTo(ComplaintStatus.TRIAGE);
        assertThat(tasks.findByComplaintId(complaint.getId())).isEmpty();
    }

    @Test
    @DisplayName("a floor nobody covers is assigned anyway, to the least loaded — section 6.4")
    void anUncoveredFloorStillGetsSomebody() {
        covers(ravi, secondFloor, Coverage.PRIMARY);
        covers(kumar, secondFloor, Coverage.SECONDARY);

        Complaint complaint = arrive("tube light not working in my room", "105");

        assertThat(complaint.getStatus())
                .describedAs("work cannot vanish because nobody is rostered to that floor")
                .isEqualTo(ComplaintStatus.ASSIGNED);
        assertThat(holderOf(complaint)).isPresent();
    }

    @Test
    @DisplayName("every complaint leaves intake owned or triaged, and none of them at NEW — R1")
    void nothingIsLeftWithoutAnOwner() {
        covers(ravi, groundFloor, Coverage.PRIMARY);

        List<Complaint> arrived =
                List.of(
                        arrive("light flickering", "101"),
                        arrive("something is wrong in my room", "202", "Others"),
                        arrive("seepage on the wall", "unknown", "Civil"));

        assertThat(arrived)
                .extracting(Complaint::getStatus)
                .doesNotContain(ComplaintStatus.NEW)
                .allSatisfy(
                        status ->
                                assertThat(status)
                                        .isIn(ComplaintStatus.ASSIGNED, ComplaintStatus.TRIAGE));
    }
}
