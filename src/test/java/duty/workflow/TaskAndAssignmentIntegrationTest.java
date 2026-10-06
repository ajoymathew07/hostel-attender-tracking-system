package duty.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import duty.identity.infrastructure.AppUserRepository;
import duty.support.AbstractIntegrationTest;
import duty.support.Fixtures;
import duty.workflow.domain.AssignMethod;
import duty.workflow.domain.AssignmentStatus;
import duty.workflow.domain.Complaint;
import duty.workflow.domain.Task;
import duty.workflow.domain.TaskAssignment;
import duty.workflow.domain.TaskStatus;
import duty.workflow.domain.TaskType;
import duty.workflow.infrastructure.ComplaintRepository;
import duty.workflow.infrastructure.TaskAssignmentRepository;
import duty.workflow.infrastructure.TaskRepository;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

/** Tasks and assignments: the two task kinds, the shared flag, and {@code score_snapshot}. */
@SpringBootTest
class TaskAndAssignmentIntegrationTest extends AbstractIntegrationTest {

    @Autowired private TaskRepository tasks;
    @Autowired private TaskAssignmentRepository assignments;
    @Autowired private ComplaintRepository complaints;
    @Autowired private AppUserRepository users;
    @Autowired private TransactionTemplate tx;

    private Long managerId;
    private Long attenderId;

    @BeforeEach
    void provision() {
        this.managerId = users.saveAndFlush(Fixtures.manager()).getId();
        this.attenderId = users.saveAndFlush(Fixtures.attender()).getId();
    }

    @Test
    @DisplayName("a task defaults to one slot, so an unshared duty cannot be double-claimed")
    void unsharedTaskHasExactlyOneSlot() {
        Long id =
                tx.execute(
                        st ->
                                tasks
                                        .save(
                                                Task.specialDuty(
                                                        "Sweep the roof",
                                                        3,
                                                        managerId,
                                                        Instant.parse("2026-03-01T04:00:00Z")))
                                        .getId());

        Task reloaded = tx.execute(st -> tasks.findById(id).orElseThrow());
        assertThat(reloaded.getSlots()).isEqualTo(1);
        assertThat(reloaded.isShared()).isFalse();
    }

    @Test
    @DisplayName("a shared duty carries its k — test X5's 'k=1 duty, 3 receive NO_SLOTS_LEFT'")
    void sharedDutyCarriesItsSlotCount() {
        Long id =
                tx.execute(
                        st -> {
                            Task duty =
                                    Task.specialDuty(
                                            "Clear the RO plant",
                                            5,
                                            managerId,
                                            Instant.parse("2026-03-01T04:00:00Z"));
                            duty.shareAcross(3);
                            return tasks.save(duty).getId();
                        });

        Task reloaded = tx.execute(st -> tasks.findById(id).orElseThrow());
        assertThat(reloaded.isShared()).isTrue();
        assertThat(reloaded.getSlots()).isEqualTo(3);
    }

    @Test
    @DisplayName("fewer than one slot is rejected before it reaches the database")
    void slotsBelowOneIsRejected() {
        Task duty =
                Task.specialDuty(
                        "Impossible", 1, managerId, Instant.parse("2026-03-01T04:00:00Z"));
        assertThatThrownBy(() -> duty.shareAcross(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 1");
    }

    @Test
    @DisplayName("a complaint task defaults to +2 and carries its complaint")
    void complaintTaskCarriesItsComplaint() {
        Complaint complaint = complaints.saveAndFlush(Fixtures.complaint());
        Task task =
                tasks.saveAndFlush(
                        Task.forComplaint(
                                complaint,
                                "No water — B wing second floor",
                                Task.DEFAULT_COMPLAINT_POINTS,
                                managerId,
                                Instant.parse("2026-02-01T07:00:00Z")));

        Task reloaded = tasks.findById(task.getId()).orElseThrow();
        assertThat(reloaded.getTaskType()).isEqualTo(TaskType.COMPLAINT);
        assertThat(reloaded.getComplaint().getId()).isEqualTo(complaint.getId());
        assertThat(reloaded.getPointValue()).isEqualTo(2);
        assertThat(reloaded.getTitle()).isEqualTo("No water — B wing second floor");
        assertThat(reloaded.getStatus()).isEqualTo(TaskStatus.OPEN);
        assertThat(reloaded.isShared()).isFalse();
        assertThat(reloaded.getCreatedBy()).isEqualTo(managerId);
        assertThat(reloaded.getCreatedAt()).isEqualTo(Instant.parse("2026-02-01T07:00:00Z"));
        assertThat(reloaded.getDescription()).isNull();
        assertThat(reloaded.getOpensAt()).isNull();
        assertThat(reloaded.getClosesAt()).isNull();
    }

    @Test
    @DisplayName("a shared special duty is posted with a window and a custom point value")
    void sharedSpecialDutyRoundTrips() {
        Task duty =
                Task.specialDuty(
                        "Common room overhaul", 5, managerId, Instant.parse("2026-02-01T07:00:00Z"));
        duty.describeAs("Clear the store room and repaint the notice board");
        duty.openBetween(Instant.parse("2026-02-02T03:30:00Z"), Instant.parse("2026-02-09T03:30:00Z"));
        duty.markShared();
        tasks.saveAndFlush(duty);

        Task reloaded = tasks.findById(duty.getId()).orElseThrow();
        assertThat(reloaded.getTaskType()).isEqualTo(TaskType.SPECIAL_DUTY);
        assertThat(reloaded.getComplaint())
                .describedAs("ck_task_link: a special duty has no complaint and no docket")
                .isNull();
        assertThat(reloaded.getPointValue()).isEqualTo(5);
        assertThat(reloaded.isShared()).isTrue();
        assertThat(reloaded.getDescription())
                .isEqualTo("Clear the store room and repaint the notice board");
        assertThat(reloaded.getOpensAt()).isEqualTo(Instant.parse("2026-02-02T03:30:00Z"));
        assertThat(reloaded.getClosesAt()).isEqualTo(Instant.parse("2026-02-09T03:30:00Z"));
        assertThat(tasks.findByStatusAndTaskType(TaskStatus.OPEN, TaskType.SPECIAL_DUTY))
                .extracting(Task::getId)
                .contains(reloaded.getId());
    }

    @Test
    @DisplayName("a duty cannot close before it opens")
    void dutyWindowIsChecked() {
        Task duty = Task.specialDuty("Backwards", 1, managerId, Instant.parse("2026-02-01T07:00:00Z"));

        assertThatThrownBy(
                        () ->
                                duty.openBetween(
                                        Instant.parse("2026-02-09T03:30:00Z"),
                                        Instant.parse("2026-02-02T03:30:00Z")))
                .isInstanceOf(IllegalArgumentException.class);

        // An open-ended window is fine.
        duty.openBetween(Instant.parse("2026-02-02T03:30:00Z"), null);
        assertThat(duty.getClosesAt()).isNull();
    }

    @Test
    @DisplayName("a task moves through its lifecycle: OPEN, CLAIMED, COMPLETED")
    void taskMovesThroughItsLifecycle() {
        Task duty =
                tasks.saveAndFlush(
                        Task.specialDuty(
                                "Sweep the terrace", 3, managerId, Instant.parse("2026-02-01T07:00:00Z")));

        duty.moveTo(TaskStatus.CLAIMED);
        Task claimed = tasks.saveAndFlush(duty);
        assertThat(tasks.findById(duty.getId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.CLAIMED);

        claimed.moveTo(TaskStatus.COMPLETED);
        tasks.saveAndFlush(claimed);
        assertThat(tasks.findById(duty.getId()).orElseThrow().getStatus())
                .isEqualTo(TaskStatus.COMPLETED);

        assertThat(TaskStatus.values())
                .containsExactly(
                        TaskStatus.OPEN,
                        TaskStatus.CLAIMED,
                        TaskStatus.COMPLETED,
                        TaskStatus.CANCELLED,
                        TaskStatus.EXPIRED);
    }

    @Test
    @DisplayName("SELECT ... FOR UPDATE loads the task for the claim path — 7.6, ADR-006")
    void taskCanBeLoadedForUpdate() {
        Task duty =
                tasks.saveAndFlush(
                        Task.specialDuty(
                                "Contended duty", 3, managerId, Instant.parse("2026-02-01T07:00:00Z")));

        // The pessimistic lock is only meaningful inside a transaction, and Spring Data
        // refuses the query outside one rather than silently dropping the FOR UPDATE.
        java.util.Optional<Task> locked = tx.execute(status -> tasks.findByIdForUpdate(duty.getId()));
        assertThat(locked).get().extracting(Task::getId).isEqualTo(duty.getId());

        assertThatThrownBy(() -> tasks.findByIdForUpdate(duty.getId()))
                .describedAs("a lock nobody holds would be a lock that protects nothing")
                .isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class);
    }

    @Test
    @DisplayName("an assignment records why it was made, then is released or completed")
    void assignmentRecordsItsScoreAndOutcome() {
        Task duty =
                tasks.saveAndFlush(
                        Task.specialDuty(
                                "Store room", 4, managerId, Instant.parse("2026-02-01T07:00:00Z")));

        TaskAssignment assignment =
                new TaskAssignment(
                        duty,
                        attenderId,
                        AssignMethod.AUTO,
                        managerId,
                        Instant.parse("2026-02-01T07:05:00Z"));
        assignment.recordScoreSnapshot("{\"floor\":1.0,\"load\":0.4,\"total\":1.4}");
        assignments.saveAndFlush(assignment);

        TaskAssignment reloaded = assignments.findById(assignment.getId()).orElseThrow();
        assertThat(reloaded.getTask().getId()).isEqualTo(duty.getId());
        assertThat(reloaded.getAttenderId()).isEqualTo(attenderId);
        assertThat(reloaded.getAssignMethod()).isEqualTo(AssignMethod.AUTO);
        assertThat(reloaded.getAssignedBy()).isEqualTo(managerId);
        assertThat(reloaded.getAssignedAt()).isEqualTo(Instant.parse("2026-02-01T07:05:00Z"));
        assertThat(reloaded.getScoreSnapshot())
                .describedAs("how a manager is told why this attender was chosen")
                .contains("\"total\":1.4");
        assertThat(reloaded.getStatus()).isEqualTo(AssignmentStatus.ACTIVE);
        assertThat(reloaded.isActive()).isTrue();

        reloaded.complete();
        TaskAssignment completed = assignments.saveAndFlush(reloaded);
        assertThat(completed.getStatus()).isEqualTo(AssignmentStatus.COMPLETED);
        assertThat(completed.isActive()).isFalse();
        assertThat(assignments.findByTaskIdAndStatus(duty.getId(), AssignmentStatus.COMPLETED))
                .hasSize(1);
        assertThat(assignments.countByTaskIdAndStatus(duty.getId(), AssignmentStatus.ACTIVE)).isZero();
    }

    @Test
    @DisplayName("releasing hands the work back — T6 reassign and T8 attender release")
    void releasingHandsTheWorkBack() {
        Task duty =
                tasks.saveAndFlush(
                        Task.specialDuty(
                                "Released duty", 2, managerId, Instant.parse("2026-02-01T07:00:00Z")));
        TaskAssignment assignment =
                assignments.saveAndFlush(
                        new TaskAssignment(
                                duty,
                                attenderId,
                                AssignMethod.MANUAL,
                                managerId,
                                Instant.parse("2026-02-01T07:05:00Z")));

        assignment.release();
        assignments.saveAndFlush(assignment);

        assertThat(assignments.findByTaskIdAndAttenderId(duty.getId(), attenderId))
                .get()
                .extracting(TaskAssignment::getStatus)
                .isEqualTo(AssignmentStatus.RELEASED);
        assertThat(assignments.findByAttenderIdAndStatus(attenderId, AssignmentStatus.ACTIVE))
                .describedAs("the attender's queue no longer shows it — E25 is an empty state")
                .isEmpty();
        assertThat(assignments.findByAttenderIdAndStatus(attenderId, AssignmentStatus.RELEASED))
                .hasSize(1);
    }

    @Test
    @DisplayName("a shared duty holds several ACTIVE assignments at once — 7.5")
    void sharedDutyHoldsSeveralAssignees() {
        Task duty =
                Task.specialDuty(
                        "Common room overhaul", 5, managerId, Instant.parse("2026-02-01T07:00:00Z"));
        duty.markShared();
        tasks.saveAndFlush(duty);

        Long second = users.saveAndFlush(Fixtures.attender()).getId();
        for (Long assignee : java.util.List.of(attenderId, second)) {
            assignments.saveAndFlush(
                    new TaskAssignment(
                            duty,
                            assignee,
                            AssignMethod.CLAIMED,
                            null,
                            Instant.parse("2026-02-01T07:05:00Z")));
        }

        assertThat(assignments.countByTaskIdAndStatus(duty.getId(), AssignmentStatus.ACTIVE))
                .isEqualTo(2);
        assertThat(assignments.findByTaskIdAndStatus(duty.getId(), AssignmentStatus.ACTIVE))
                .allSatisfy(a -> assertThat(a.getAssignedBy()).isNull());
    }
}
