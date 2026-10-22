package duty.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import duty.audit.application.AuditTrail.AuditRecord;
import duty.audit.application.AuditTrail;
import duty.identity.domain.AppUser;
import duty.identity.infrastructure.AppUserRepository;
import duty.ledger.domain.PointLedgerEntry;
import duty.ledger.infrastructure.PointLedgerRepository;
import duty.support.AbstractIntegrationTest;
import duty.support.Fixtures;
import duty.workflow.application.ManualAssignmentService;
import duty.workflow.application.TaskResolutionService;
import duty.workflow.application.WorkflowService.GuardFailedException;
import duty.workflow.application.WorkflowService.TransitionPayload;
import duty.workflow.application.WorkflowService;
import duty.workflow.domain.Complaint.ComplaintStatus;
import duty.workflow.domain.ComplaintTransitions.WorkflowEvent;
import duty.workflow.domain.TaskAssignment.AssignmentStatus;
import duty.workflow.infrastructure.ComplaintRepository;
import duty.workflow.infrastructure.TaskAssignmentRepository;
import duty.workflow.infrastructure.TaskRepository;
import java.util.List;
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

/** Act-as, the seven-day auto-close, refusing to assign finished work, and the X3 race. */
@SpringBootTest
class ManagerOversightIntegrationTest extends AbstractIntegrationTest {

    private static final String DOCKET = "EU/2026/00517";

    @Autowired private WorkflowService workflow;
    @Autowired private TaskResolutionService resolution;
    @Autowired private ManualAssignmentService assignment;
    @Autowired private ComplaintRepository complaints;
    @Autowired private TaskRepository tasks;
    @Autowired private TaskAssignmentRepository assignments;
    @Autowired private PointLedgerRepository ledger;
    @Autowired private AuditTrail audit;
    @Autowired private AppUserRepository users;
    @Autowired private JdbcTemplate jdbc;

    private AppUser manager;
    private AppUser ravi;
    private AppUser kumar;
    private long complaintId;
    private long taskId;

    @BeforeEach
    void assignAComplaintToRavi() {
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

    private ComplaintStatus status() {
        return complaints.findById(complaintId).orElseThrow().getStatus();
    }

    private void bringToInProgressAsRavi() {
        Fixtures.signIn(ravi);
        resolution.startInspection(taskId);
        resolution.confirmDefect(taskId);
        resolution.recordDocket(taskId, DOCKET);
    }

    @Test
    @DisplayName("act-as runs the attender's steps; the audit names both and the +2 goes to the attender")
    void theManagerActsForTheAttender() {
        Fixtures.signIn(manager);
        resolution.actAs(complaintId, WorkflowEvent.ATTENDER_START, null);
        resolution.actAs(complaintId, WorkflowEvent.DEFECT_CONFIRMED, null);
        resolution.actAs(complaintId, WorkflowEvent.DOCKET_RECORDED, DOCKET);
        resolution.actAs(complaintId, WorkflowEvent.MARK_RESOLVED, null);

        assertThat(status()).isEqualTo(ComplaintStatus.RESOLVED);
        List<PointLedgerEntry> credits = ledger.findByAttenderIdOrderByCreatedAtDesc(ravi.getId());
        assertThat(credits).hasSize(1);
        assertThat(credits.get(0).getPoints()).isEqualTo(2);
        assertThat(credits.get(0).getCreatedBy()).describedAs("the manager typed it").isEqualTo(manager.getId());

        List<AuditRecord> steps =
                audit.historyOf("complaint", complaintId).stream()
                        .filter(event -> event.onBehalfOf() != null)
                        .toList();
        assertThat(steps).hasSize(4);
        assertThat(steps)
                .allSatisfy(
                        event -> {
                            assertThat(event.actorId()).isEqualTo(manager.getId());
                            assertThat(event.onBehalfOf()).isEqualTo(ravi.getId());
                        });
    }

    @Test
    @DisplayName("act-as keeps the guards: no docket, no resolve")
    void actAsKeepsTheGuards() {
        Fixtures.signIn(manager);
        resolution.actAs(complaintId, WorkflowEvent.ATTENDER_START, null);

        assertThatThrownBy(() -> resolution.actAs(complaintId, WorkflowEvent.MARK_RESOLVED, null))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> resolution.actAs(complaintId, WorkflowEvent.MANAGER_CLOSE, null))
                .isInstanceOf(GuardFailedException.class);
        assertThat(ledger.balanceOf(ravi.getId(), null)).isZero();
    }

    @Test
    @DisplayName("only the manager can act for someone, through the service or the payload")
    void attendersCannotActAs() {
        Fixtures.signIn(kumar);
        assertThatThrownBy(() -> resolution.actAs(complaintId, WorkflowEvent.ATTENDER_START, null))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(
                        () ->
                                workflow.transition(
                                        complaintId,
                                        WorkflowEvent.ATTENDER_START,
                                        TransitionPayload.none().actingFor(ravi.getId())))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(status()).isEqualTo(ComplaintStatus.ASSIGNED);
    }

    @Test
    @DisplayName("a complaint resolved over seven days ago is auto-closed; a recent one is not")
    void resolvedWorkAutoClosesAfterSevenDays() {
        bringToInProgressAsRavi();
        resolution.resolve(taskId);
        SecurityContextHolder.clearContext();

        workflow.autoCloseResolved();
        assertThat(status()).describedAs("resolved just now").isEqualTo(ComplaintStatus.RESOLVED);

        jdbc.update(
                "update complaint set resolved_at = (now() at time zone 'UTC') - interval '8 days' where id = ?",
                complaintId);
        workflow.autoCloseResolved();

        assertThat(status()).isEqualTo(ComplaintStatus.CLOSED);
    }

    @Test
    @DisplayName("finished work cannot be assigned to anyone")
    void finishedWorkCannotBeAssigned() {
        bringToInProgressAsRavi();
        resolution.resolve(taskId);

        Fixtures.signIn(manager);
        assertThatThrownBy(() -> assignment.assign(complaintId, kumar.getId()))
                .isInstanceOf(GuardFailedException.class);
        assertThat(assignments.findByTaskIdAndAttenderId(taskId, kumar.getId())).isEmpty();
    }

    @Test
    @DisplayName("the manager reassigns while the attender resolves: one wins, the ledger matches — X3")
    void reassignRacesResolve() throws Exception {
        bringToInProgressAsRavi();
        SecurityContextHolder.clearContext();

        CountDownLatch startTogether = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> resolve =
                    pool.submit(
                            () -> attempt(startTogether, ravi, () -> resolution.resolve(taskId), succeeded));
            Future<?> reassign =
                    pool.submit(
                            () ->
                                    attempt(
                                            startTogether,
                                            manager,
                                            () -> assignment.assign(complaintId, kumar.getId()),
                                            succeeded));
            startTogether.countDown();
            resolve.get(30, TimeUnit.SECONDS);
            reassign.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(succeeded.get()).describedAs("exactly one of the two wins").isEqualTo(1);
        boolean kumarHoldsIt =
                assignments
                        .findByTaskIdAndAttenderId(taskId, kumar.getId())
                        .map(held -> held.getStatus() == AssignmentStatus.ACTIVE)
                        .orElse(false);
        if (status() == ComplaintStatus.RESOLVED) {
            assertThat(ledger.balanceOf(ravi.getId(), null)).isEqualTo(2L);
            assertThat(kumarHoldsIt).isFalse();
        } else {
            assertThat(ledger.balanceOf(ravi.getId(), null)).isZero();
            assertThat(kumarHoldsIt).isTrue();
        }
    }

    /** Waits for the latch, acts as {@code user}, and counts a success; a refusal is the expected loss. */
    private static void attempt(CountDownLatch start, AppUser user, Runnable action, AtomicInteger succeeded) {
        try {
            start.await(10, TimeUnit.SECONDS);
            Fixtures.signIn(user);
            action.run();
            succeeded.incrementAndGet();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException lost) {
            // Optimistic lock, a released assignment, or a finished complaint: the loser is told.
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
