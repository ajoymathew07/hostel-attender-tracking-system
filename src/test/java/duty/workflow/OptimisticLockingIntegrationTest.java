package duty.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import duty.identity.domain.AppUser;
import duty.identity.infrastructure.AppUserRepository;
import duty.support.AbstractIntegrationTest;
import duty.support.Fixtures;
import duty.workflow.domain.AssignMethod;
import duty.workflow.domain.AssignmentStatus;
import duty.workflow.domain.Complaint;
import duty.workflow.domain.ComplaintStatus;
import duty.workflow.domain.Task;
import duty.workflow.domain.TaskAssignment;
import duty.workflow.infrastructure.ComplaintRepository;
import duty.workflow.infrastructure.TaskAssignmentRepository;
import duty.workflow.infrastructure.TaskRepository;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Optimistic locking — mechanism M1 of section 7.2, guarding the lost update C2: a manager
 * reassigning a task at the same moment the attender resolves it.
 *
 * <p>Section 7.4 puts {@code @Version} on {@code complaint}, {@code task}, {@code task_assignment},
 * {@code floor_assignment} and {@code app_user}. The loser of a race does not silently overwrite
 * the winner; it fails, and the caller turns that into a 409 that says what actually happened.
 */
@SpringBootTest
class OptimisticLockingIntegrationTest extends AbstractIntegrationTest {

    @Autowired private ComplaintRepository complaints;
    @Autowired private TaskRepository tasks;
    @Autowired private TaskAssignmentRepository assignments;
    @Autowired private AppUserRepository users;
    @Autowired private TransactionTemplate tx;

    @Test
    @DisplayName("two concurrent updates of a complaint: one wins, the loser fails rather than overwriting")
    void concurrentComplaintUpdatesConflict() throws Exception {
        Long id = complaints.saveAndFlush(Fixtures.complaint()).getId();

        // Both actors loaded the row before either wrote: each holds version 0.
        Complaint managersCopy = tx.execute(status -> complaints.findById(id).orElseThrow());
        Complaint attendersCopy = tx.execute(status -> complaints.findById(id).orElseThrow());
        assertThat(managersCopy.getVersion()).isEqualTo(attendersCopy.getVersion()).isEqualTo(0L);

        // The manager commits first.
        tx.executeWithoutResult(
                status -> {
                    managersCopy.moveTo(ComplaintStatus.TRIAGE, Instant.parse("2026-02-02T10:00:00Z"));
                    complaints.saveAndFlush(managersCopy);
                });

        // The attender's write is based on a version that has moved on.
        attendersCopy.moveTo(ComplaintStatus.INSPECTING, Instant.parse("2026-02-02T10:00:01Z"));
        assertThatThrownBy(
                        () -> tx.executeWithoutResult(status -> complaints.saveAndFlush(attendersCopy)))
                .describedAs("the second writer must be told, not silently allowed to clobber")
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        Complaint winner = complaints.findById(id).orElseThrow();
        assertThat(winner.getStatus())
                .describedAs("the winner's change stands, whole")
                .isEqualTo(ComplaintStatus.TRIAGE);
        assertThat(winner.getVersion()).isEqualTo(1L);
    }

    @Test
    @DisplayName("@Version increments on every update, which is what makes the check work")
    void versionIncrementsOnEachUpdate() {
        Long id = complaints.saveAndFlush(Fixtures.complaint()).getId();

        for (int expected = 1; expected <= 3; expected++) {
            int round = expected;
            tx.executeWithoutResult(
                    status -> {
                        Complaint complaint = complaints.findById(id).orElseThrow();
                        complaint.escalatePriority(Instant.parse("2026-02-0" + round + "T10:00:00Z"));
                        complaints.saveAndFlush(complaint);
                    });
            assertThat(complaints.findById(id).orElseThrow().getVersion()).isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("app_user carries @Version too — a manager and a self-service edit cannot both win")
    void appUserIsVersioned() {
        Long id = users.saveAndFlush(Fixtures.attender()).getId();

        AppUser first = tx.execute(status -> users.findById(id).orElseThrow());
        AppUser second = tx.execute(status -> users.findById(id).orElseThrow());

        tx.executeWithoutResult(
                status -> {
                    first.rename("Renamed by the manager");
                    users.saveAndFlush(first);
                });

        second.deactivate();
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> users.saveAndFlush(second)))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        AppUser reloaded = users.findById(id).orElseThrow();
        assertThat(reloaded.getDisplayName()).isEqualTo("Renamed by the manager");
        assertThat(reloaded.isActive()).describedAs("the losing deactivation did not take").isTrue();
    }

    @Test
    @DisplayName("task_assignment is versioned, and uq_task_attender blocks a double assignment outright")
    void assignmentIsVersionedAndUnique() {
        Long managerId = users.saveAndFlush(Fixtures.manager()).getId();
        Long attenderId = users.saveAndFlush(Fixtures.attender()).getId();
        Complaint complaint = complaints.saveAndFlush(Fixtures.complaint());
        Task task =
                tasks.saveAndFlush(
                        Task.forComplaint(
                                complaint,
                                "Fix the RO plant",
                                Task.DEFAULT_COMPLAINT_POINTS,
                                managerId,
                                Instant.parse("2026-02-01T07:00:00Z")));

        TaskAssignment assignment =
                assignments.saveAndFlush(
                        new TaskAssignment(
                                task,
                                attenderId,
                                AssignMethod.AUTO,
                                managerId,
                                Instant.parse("2026-02-01T07:01:00Z")));
        assertThat(assignment.getVersion()).isEqualTo(0L);
        assertThat(assignment.isActive()).isTrue();

        // uq_task_attender: the same attender cannot hold the same task twice, whatever the path.
        assertThatThrownBy(
                        () ->
                                assignments.saveAndFlush(
                                        new TaskAssignment(
                                                task,
                                                attenderId,
                                                AssignMethod.CLAIMED,
                                                managerId,
                                                Instant.parse("2026-02-01T07:02:00Z"))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Note this does not depend on contention. All four copies are detached at version 0 before the
     * threads are released, so the outcome is the same run sequentially — what it pins down is the
     * {@code @Version} check against a stale copy, which is the behaviour that matters when a
     * manager reassigns while an attender is mid-resolve. The genuinely contended case is
     * {@code LedgerIdempotencyIntegrationTest}, where the losers block inside Postgres.
     */
    @Test
    @DisplayName("a stale copy loses to a committed write, so only one of four updates survives")
    void staleCopiesLoseToTheCommittedWrite() throws Exception {
        Long managerId = users.saveAndFlush(Fixtures.manager()).getId();
        Complaint complaint = complaints.saveAndFlush(Fixtures.complaint());
        Long taskId =
                tasks.saveAndFlush(
                                Task.forComplaint(
                                        complaint,
                                        "Shared overhaul",
                                        4,
                                        managerId,
                                        Instant.parse("2026-02-01T07:00:00Z")))
                        .getId();

        int threads = 4;
        // Every thread loads the row up front, so all four hold the same version before any writes.
        List<Task> staleCopies =
                java.util.stream.IntStream.range(0, threads)
                        .mapToObj(i -> tx.execute(status -> tasks.findById(taskId).orElseThrow()))
                        .toList();

        CountDownLatch startTogether = new CountDownLatch(1);
        AtomicInteger won = new AtomicInteger();
        AtomicInteger lost = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Object>> futures =
                    staleCopies.stream()
                            .map(
                                    stale ->
                                            pool.submit(
                                                    () -> {
                                                        startTogether.await(10, TimeUnit.SECONDS);
                                                        try {
                                                            tx.executeWithoutResult(
                                                                    status -> {
                                                                        stale.markShared();
                                                                        tasks.saveAndFlush(stale);
                                                                    });
                                                            won.incrementAndGet();
                                                        } catch (ObjectOptimisticLockingFailureException
                                                                expected) {
                                                            lost.incrementAndGet();
                                                        }
                                                        return null;
                                                    }))
                            .toList();
            startTogether.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(won.get()).describedAs("exactly one writer commits against version 0").isEqualTo(1);
        assertThat(lost.get()).isEqualTo(threads - 1);
        assertThat(tasks.findById(taskId).orElseThrow().getVersion())
                .describedAs("no lost update: the version moved exactly once")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("ck_task_link: a complaint task must carry a complaint and a duty must not")
    void taskLinkCheckConstraintHolds() {
        Long managerId = users.saveAndFlush(Fixtures.manager()).getId();

        Task duty =
                tasks.saveAndFlush(
                        Task.specialDuty(
                                "Common room overhaul",
                                5,
                                managerId,
                                Instant.parse("2026-02-01T07:00:00Z")));
        assertThat(duty.getComplaint()).isNull();

        Complaint complaint = complaints.saveAndFlush(Fixtures.complaint());
        Task complaintTask =
                tasks.saveAndFlush(
                        Task.forComplaint(
                                complaint,
                                "Fix the tap",
                                Task.DEFAULT_COMPLAINT_POINTS,
                                managerId,
                                Instant.parse("2026-02-01T07:00:00Z")));
        assertThat(tasks.findByComplaintId(complaint.getId()))
                .get()
                .extracting(Task::getId)
                .isEqualTo(complaintTask.getId());
        assertThat(assignments.countByTaskIdAndStatus(complaintTask.getId(), AssignmentStatus.ACTIVE))
                .isZero();
    }
}
