package duty.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import duty.identity.domain.AppUser;
import duty.identity.infrastructure.AppUserRepository;
import duty.ledger.application.LeaderboardRow;
import duty.ledger.application.LeaderboardService;
import duty.ledger.domain.ReasonCode;
import duty.ledger.infrastructure.PointLedgerRepository;
import duty.support.AbstractIntegrationTest;
import duty.support.Fixtures;
import duty.workflow.application.DocketRequiredException;
import duty.workflow.application.InvalidDocketException;
import duty.workflow.application.ManualAssignmentService;
import duty.workflow.application.ResolvedTask;
import duty.workflow.application.TaskResolutionService;
import duty.workflow.application.TransitionPayload;
import duty.workflow.application.WorkflowService;
import duty.workflow.domain.AssignmentStatus;
import duty.workflow.domain.ComplaintStatus;
import duty.workflow.domain.TaskStatus;
import duty.workflow.domain.WorkflowEvent;
import duty.workflow.infrastructure.ComplaintRepository;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Resolving a task: the +2, the docket gate, and the guarantee the ledger exists for.
 *
 * <p>{@code LedgerIdempotencyIntegrationTest} proves that the database cannot hold two rows under
 * one key. This class proves the thing above it — that the resolve path really does key its credit
 * to the work, so the attender who taps twice on a hostel corridor's Wi-Fi is credited once.
 */
@SpringBootTest
class TaskResolutionIntegrationTest extends AbstractIntegrationTest {

    private static final String DOCKET = "EU/2026/00417";

    @Autowired private TaskResolutionService resolution;
    @Autowired private ManualAssignmentService assignment;
    @Autowired private WorkflowService workflow;
    @Autowired private ComplaintRepository complaints;
    @Autowired private TaskRepository tasks;
    @Autowired private TaskAssignmentRepository assignments;
    @Autowired private PointLedgerRepository ledger;
    @Autowired private LeaderboardService leaderboard;
    @Autowired private AppUserRepository users;

    private AppUser manager;
    private AppUser ravi;
    private AppUser kumar;
    private long complaintId;
    private long taskId;

    @BeforeEach
    void assignSomeWork() {
        manager = users.saveAndFlush(Fixtures.manager());
        ravi = users.saveAndFlush(Fixtures.attender());
        kumar = users.saveAndFlush(Fixtures.attender());

        complaintId = complaints.saveAndFlush(Fixtures.complaintInTriage()).getId();
        Fixtures.signIn(manager);
        assignment.assign(complaintId, ravi.getId());
        taskId = tasks.findByComplaintId(complaintId).orElseThrow().getId();
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    /**
     * T7 then T9 — the two steps of section 10.2's flow that come before the docket.
     *
     * <p>They are here because the transition table is now the only write path: a complaint
     * reaches {@code AWAITING_CMS} by being started and inspected, not by a service deciding to
     * skip ahead. Before PR 11 these two states did not exist in code and the docket was filed
     * straight from {@code ASSIGNED}.
     */
    private void startAndConfirmDefectAsRavi() {
        Fixtures.signIn(ravi);
        workflow.transition(complaintId, WorkflowEvent.ATTENDER_START, TransitionPayload.none());
        workflow.transition(complaintId, WorkflowEvent.DEFECT_CONFIRMED, TransitionPayload.none());
    }

    private void fileDocketAsRavi() {
        startAndConfirmDefectAsRavi();
        resolution.recordDocket(taskId, DOCKET);
    }

    @Test
    @DisplayName("resolving credits exactly +2, once, under the work-derived key — T14")
    void resolvingCreditsTwoPoints() {
        fileDocketAsRavi();

        ResolvedTask resolved = resolution.resolve(taskId);

        assertThat(resolved.pointsAwarded()).isEqualTo(2);
        assertThat(resolved.alreadyApplied()).isFalse();
        assertThat(resolved.periodTotal()).isEqualTo(2L);

        assertThat(ledger.findByAttenderIdOrderByCreatedAtDesc(ravi.getId()))
                .singleElement()
                .satisfies(
                        entry -> {
                            assertThat(entry.getPoints()).isEqualTo(2);
                            assertThat(entry.getReasonCode()).isEqualTo(ReasonCode.COMPLAINT_RESOLVED);
                            assertThat(entry.getTaskId()).isEqualTo(taskId);
                            assertThat(entry.getIdempotencyKey())
                                    .describedAs("derived from the work, not from the request — 7.3")
                                    .isEqualTo("RESOLVE:" + taskId + ":" + ravi.getId());
                        });
    }

    @Test
    @DisplayName("resolving completes the assignment and the task, and stamps resolved_at")
    void resolvingClosesTheWorkAndStartsTheSevenDayWindow() {
        Instant before = Instant.now();
        fileDocketAsRavi();

        resolution.resolve(taskId);

        assertThat(assignments.findByTaskIdAndAttenderId(taskId, ravi.getId()).orElseThrow())
                .extracting(a -> a.getStatus())
                .isEqualTo(AssignmentStatus.COMPLETED);
        assertThat(tasks.findById(taskId).orElseThrow().getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(complaints.findById(complaintId).orElseThrow())
                .satisfies(
                        complaint -> {
                            assertThat(complaint.getStatus()).isEqualTo(ComplaintStatus.RESOLVED);
                            assertThat(complaint.getResolvedAt())
                                    .describedAs("the 7-day PII window starts here — 11.4, T16")
                                    .isNotNull()
                                    .isAfterOrEqualTo(before);
                        });
    }

    @Test
    @DisplayName("tapping Resolve twice credits once — the second is a no-op, not an error — C1")
    void resolvingTwiceCreditsOnce() {
        fileDocketAsRavi();

        resolution.resolve(taskId);
        ResolvedTask replay = resolution.resolve(taskId);

        assertThat(replay.alreadyApplied())
                .describedAs("the retry is a clean success, not a swallowed conflict — 7.4")
                .isTrue();
        assertThat(replay.pointsAwarded()).isZero();
        assertThat(replay.periodTotal()).isEqualTo(2L);
        assertThat(ledger.findByAttenderIdOrderByCreatedAtDesc(ravi.getId()))
                .describedAs("+2 for one job, never +4")
                .hasSize(1);
    }

    @Test
    @DisplayName("a replay does not restart the 7-day window on the resident's details")
    void aReplayDoesNotRestampResolvedAt() {
        fileDocketAsRavi();
        resolution.resolve(taskId);
        Instant stamped = complaints.findById(complaintId).orElseThrow().getResolvedAt();

        resolution.resolve(taskId);

        assertThat(complaints.findById(complaintId).orElseThrow().getResolvedAt())
                .describedAs("re-stamping would silently give the attender another 7 days of PII")
                .isEqualTo(stamped);
    }

    @Test
    @DisplayName("ten concurrent resolves of one task leave exactly one ledger row — X1")
    void concurrentResolvesLeaveOneRow() throws Exception {
        fileDocketAsRavi();
        int threads = 10;
        CountDownLatch startTogether = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Void>> attempts = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                attempts.add(
                        () -> {
                            // Each thread is its own request: its own security context and its own
                            // transaction, as a retried HTTP call would be.
                            Fixtures.signIn(ravi);
                            startTogether.await(10, TimeUnit.SECONDS);
                            try {
                                resolution.resolve(taskId);
                            } catch (RuntimeException losersAreExpected) {
                                // uq_ledger_idem rejected this insert. The claim under test is
                                // about the database's final state, not about who got an error.
                            } finally {
                                SecurityContextHolder.clearContext();
                            }
                            return null;
                        });
            }
            List<Future<Void>> futures = new ArrayList<>();
            for (Callable<Void> attempt : attempts) {
                futures.add(pool.submit(attempt));
            }
            startTogether.countDown();
            for (Future<Void> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(ledger.findByAttenderIdOrderByCreatedAtDesc(ravi.getId()))
                .describedAs("one row however many threads raced — the constraint, not the code")
                .hasSize(1);
        assertThat(ledger.balanceOf(ravi.getId(), null)).isEqualTo(2L);
        assertThat(complaints.findById(complaintId).orElseThrow().getStatus())
                .isEqualTo(ComplaintStatus.RESOLVED);
    }

    @Test
    @DisplayName("the +2 appears on the manager's leaderboard — the skeleton walks end to end")
    void theCreditReachesTheLeaderboard() {
        fileDocketAsRavi();
        resolution.resolve(taskId);

        Fixtures.signIn(manager);
        assertThat(leaderboard.currentStandings().rows())
                .filteredOn(row -> row.attenderId() == ravi.getId())
                .singleElement()
                .extracting(LeaderboardRow::total)
                .describedAs("the standings are a SUM over the ledger, computed at read time — 7.8")
                .isEqualTo(2L);
    }

    @Test
    @DisplayName("no docket, no points: resolving is refused and nothing reaches the ledger — E15")
    void resolvingWithoutADocketIsRefused() {
        Fixtures.signIn(ravi);

        assertThatThrownBy(() -> resolution.resolve(taskId))
                .isInstanceOf(DocketRequiredException.class);

        assertThat(ledger.findByAttenderIdOrderByCreatedAtDesc(ravi.getId())).isEmpty();
        assertThat(complaints.findById(complaintId).orElseThrow())
                .satisfies(
                        complaint -> {
                            assertThat(complaint.getStatus()).isNotEqualTo(ComplaintStatus.RESOLVED);
                            assertThat(complaint.getResolvedAt()).isNull();
                        });
    }

    @Test
    @DisplayName("a docket that does not match the configured pattern is refused — T11, OQ-1")
    void aMalformedDocketIsRefused() {
        startAndConfirmDefectAsRavi();

        assertThatThrownBy(() -> resolution.recordDocket(taskId, "  "))
                .isInstanceOf(InvalidDocketException.class);
        assertThat(complaints.findById(complaintId).orElseThrow().hasDocket()).isFalse();

        assertThatCode(() -> resolution.recordDocket(taskId, DOCKET)).doesNotThrowAnyException();
        assertThat(complaints.findById(complaintId).orElseThrow())
                .satisfies(
                        complaint -> {
                            assertThat(complaint.getDocketNo()).isEqualTo(DOCKET);
                            assertThat(complaint.getDocketFiledBy()).isEqualTo(ravi.getId());
                            assertThat(complaint.getStatus())
                                    .describedAs("filed with the Engineering Unit — T11")
                                    .isEqualTo(ComplaintStatus.IN_PROGRESS);
                        });
    }

    @Test
    @DisplayName("an attender cannot resolve another attender's task and collect the +2 — E20")
    void anotherAttendersTaskIsRefused() {
        fileDocketAsRavi();

        Fixtures.signIn(kumar);
        assertThatThrownBy(() -> resolution.resolve(taskId))
                .describedAs("ownership, not role, decides who may resolve — 11.3")
                .isInstanceOf(AccessDeniedException.class);

        assertThat(ledger.findByAttenderIdOrderByCreatedAtDesc(kumar.getId())).isEmpty();
        assertThat(ledger.findByAttenderIdOrderByCreatedAtDesc(ravi.getId())).isEmpty();
    }

    @Test
    @DisplayName("an attender whose assignment was taken away can no longer resolve it — C2")
    void aReleasedAttenderCannotResolve() {
        fileDocketAsRavi();

        Fixtures.signIn(manager);
        assignment.assign(complaintId, kumar.getId());

        Fixtures.signIn(ravi);
        assertThatThrownBy(() -> resolution.resolve(taskId))
                .describedAs("crediting the person who no longer owns the work is the C2 failure")
                .isInstanceOf(AccessDeniedException.class);
        assertThat(ledger.findByAttenderIdOrderByCreatedAtDesc(ravi.getId())).isEmpty();
    }
}
