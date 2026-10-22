package duty.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import duty.identity.domain.AppUser;
import duty.identity.infrastructure.AppUserRepository;
import duty.ledger.domain.PointLedgerEntry.IdempotencyKeys;
import duty.ledger.domain.PointLedgerEntry.ReasonCode;
import duty.ledger.infrastructure.PointLedgerRepository;
import duty.support.AbstractIntegrationTest;
import duty.support.Fixtures;
import duty.workflow.application.SpecialDutyService.NoSlotsLeftException;
import duty.workflow.application.SpecialDutyService;
import duty.workflow.application.WorkflowService.GuardFailedException;
import duty.workflow.domain.Task.TaskStatus;
import duty.workflow.domain.TaskAssignment.AssignmentStatus;
import duty.workflow.infrastructure.TaskAssignmentRepository;
import duty.workflow.infrastructure.TaskRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;

/** Special duties (section 5.2): post, claim, complete, cancel, expire, and the X4 and X5 races. */
@SpringBootTest
class SpecialDutyIntegrationTest extends AbstractIntegrationTest {

    @Autowired private SpecialDutyService duties;
    @Autowired private TaskRepository tasks;
    @Autowired private TaskAssignmentRepository assignments;
    @Autowired private PointLedgerRepository ledger;
    @Autowired private AppUserRepository users;
    @Autowired private JdbcTemplate jdbc;

    private AppUser manager;
    private List<AppUser> attenders;

    @BeforeEach
    void provisionUsers() {
        manager = users.saveAndFlush(Fixtures.manager());
        attenders = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            attenders.add(users.saveAndFlush(Fixtures.attender()));
        }
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private long post(int points, int slots, Instant closesAt) {
        Fixtures.signIn(manager);
        long id = duties.post("Water tank cleaning", "Roof tank, block B", points, slots, closesAt);
        SecurityContextHolder.clearContext();
        return id;
    }

    private void as(AppUser user, Runnable action) {
        Fixtures.signIn(user);
        try {
            action.run();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private TaskStatus statusOf(long taskId) {
        return tasks.findById(taskId).orElseThrow().getStatus();
    }

    @Test
    @DisplayName("claim then done credits the duty's points once, under DUTY:{task}:{attender}")
    void claimAndCompleteCreditsOnce() {
        long duty = post(5, 1, null);
        AppUser ravi = attenders.get(0);

        as(ravi, () -> duties.claim(duty));
        assertThat(statusOf(duty)).isEqualTo(TaskStatus.CLAIMED);

        as(ravi, () -> assertThat(duties.complete(duty)).isEqualTo(5));
        as(ravi, () -> assertThat(duties.complete(duty)).describedAs("a repeated tap").isZero());

        assertThat(statusOf(duty)).isEqualTo(TaskStatus.COMPLETED);
        assertThat(ledger.findByIdempotencyKey(IdempotencyKeys.forSpecialDuty(duty, ravi.getId())))
                .hasValueSatisfying(
                        entry -> {
                            assertThat(entry.getReasonCode()).isEqualTo(ReasonCode.SPECIAL_DUTY);
                            assertThat(entry.getPoints()).isEqualTo(5);
                        });
        assertThat(ledger.balanceOf(ravi.getId(), null)).isEqualTo(5L);
    }

    @Test
    @DisplayName("a shared duty credits each assignee once and completes when the last one finishes")
    void aSharedDutyCreditsEveryAssignee() {
        long duty = post(3, 2, null);
        AppUser ravi = attenders.get(0);
        AppUser kumar = attenders.get(1);

        as(ravi, () -> duties.claim(duty));
        assertThat(statusOf(duty)).describedAs("one place still free").isEqualTo(TaskStatus.OPEN);
        as(kumar, () -> duties.claim(duty));
        assertThat(statusOf(duty)).isEqualTo(TaskStatus.CLAIMED);

        as(ravi, () -> duties.complete(duty));
        assertThat(statusOf(duty)).describedAs("kumar is still working").isEqualTo(TaskStatus.CLAIMED);
        as(kumar, () -> duties.complete(duty));

        assertThat(statusOf(duty)).isEqualTo(TaskStatus.COMPLETED);
        assertThat(ledger.balanceOf(ravi.getId(), null)).isEqualTo(3L);
        assertThat(ledger.balanceOf(kumar.getId(), null)).isEqualTo(3L);
    }

    @Test
    @DisplayName("a full duty refuses the next claim, and nobody can claim the same duty twice")
    void claimsAreLimited() {
        long duty = post(2, 1, null);
        as(attenders.get(0), () -> duties.claim(duty));

        assertThatThrownBy(() -> as(attenders.get(0), () -> duties.claim(duty)))
                .isInstanceOf(GuardFailedException.class);
        assertThatThrownBy(() -> as(attenders.get(1), () -> duties.claim(duty)))
                .isInstanceOf(NoSlotsLeftException.class);
    }

    @Test
    @DisplayName("only the attender who claimed a duty can complete it, and a manager cannot claim")
    void roleAndOwnershipAreChecked() {
        long duty = post(2, 1, null);
        as(attenders.get(0), () -> duties.claim(duty));

        assertThatThrownBy(() -> as(attenders.get(1), () -> duties.complete(duty)))
                .isInstanceOf(AccessDeniedException.class);
        long another = post(2, 1, null);
        assertThatThrownBy(() -> as(manager, () -> duties.claim(another)))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> as(attenders.get(0), () -> duties.post("x", null, 2, 1, null)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("cancelling releases the holder; points already credited stay")
    void cancelReleasesHolders() {
        long duty = post(2, 2, null);
        AppUser ravi = attenders.get(0);
        AppUser kumar = attenders.get(1);
        as(ravi, () -> duties.claim(duty));
        as(kumar, () -> duties.claim(duty));
        as(ravi, () -> duties.complete(duty));

        as(manager, () -> duties.cancel(duty));

        assertThat(statusOf(duty)).isEqualTo(TaskStatus.CANCELLED);
        assertThat(assignments.findByTaskIdAndAttenderId(duty, kumar.getId()).orElseThrow().getStatus())
                .isEqualTo(AssignmentStatus.RELEASED);
        assertThat(ledger.balanceOf(ravi.getId(), null)).isEqualTo(2L);
        assertThatThrownBy(() -> as(kumar, () -> duties.complete(duty)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("a duty past its closing time cannot be claimed, and the expiry job closes it")
    void anOverdueDutyExpires() {
        long duty = post(2, 1, Instant.now().plusSeconds(3600));
        // Move the closing time into the past, as if the hour had gone by. Stored as UTC.
        jdbc.update(
                "update task set closes_at = (now() at time zone 'UTC') - interval '1 minute' where id = ?",
                duty);

        assertThatThrownBy(() -> as(attenders.get(0), () -> duties.claim(duty)))
                .isInstanceOf(GuardFailedException.class);

        assertThat(duties.expireOverdue()).isGreaterThanOrEqualTo(1);
        assertThat(statusOf(duty)).isEqualTo(TaskStatus.EXPIRED);
    }

    @Test
    @DisplayName("4 attenders claim a 1-place duty at once: 1 assignment, 3 told no places left — X5")
    void concurrentClaimsForOnePlace() throws Exception {
        long duty = post(2, 1, null);
        AtomicInteger noSlots = new AtomicInteger();

        race(
                attender ->
                        () -> {
                            try {
                                duties.claim(duty);
                            } catch (NoSlotsLeftException expected) {
                                noSlots.incrementAndGet();
                            }
                        });

        assertThat(assignments.countByTaskIdAndStatus(duty, AssignmentStatus.ACTIVE)).isEqualTo(1);
        assertThat(noSlots.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("4 attenders finish a shared duty at once: 4 ledger rows, completed exactly once — X4")
    void concurrentCompletionOfASharedDuty() throws Exception {
        long duty = post(2, 4, null);
        for (AppUser attender : attenders) {
            as(attender, () -> duties.claim(duty));
        }

        race(attender -> () -> duties.complete(duty));

        assertThat(statusOf(duty)).isEqualTo(TaskStatus.COMPLETED);
        for (AppUser attender : attenders) {
            assertThat(ledger.balanceOf(attender.getId(), null)).isEqualTo(2L);
        }
    }

    /** Runs one action per attender, all released together by a latch, each signed in as itself. */
    private void race(java.util.function.Function<AppUser, Runnable> actionFor) throws Exception {
        CountDownLatch startTogether = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(attenders.size());
        try {
            List<Future<Void>> futures = new ArrayList<>();
            for (AppUser attender : attenders) {
                Callable<Void> attempt =
                        () -> {
                            startTogether.await(10, TimeUnit.SECONDS);
                            as(attender, actionFor.apply(attender));
                            return null;
                        };
                futures.add(pool.submit(attempt));
            }
            startTogether.countDown();
            for (Future<Void> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
