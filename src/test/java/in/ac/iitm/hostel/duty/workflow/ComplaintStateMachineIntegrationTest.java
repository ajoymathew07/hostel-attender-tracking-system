package in.ac.iitm.hostel.duty.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.ac.iitm.hostel.duty.audit.domain.AuditEvent;
import in.ac.iitm.hostel.duty.audit.infrastructure.AuditEventRepository;
import in.ac.iitm.hostel.duty.identity.domain.AppUser;
import in.ac.iitm.hostel.duty.identity.infrastructure.AppUserRepository;
import in.ac.iitm.hostel.duty.ledger.infrastructure.PointLedgerRepository;
import in.ac.iitm.hostel.duty.support.AbstractIntegrationTest;
import in.ac.iitm.hostel.duty.support.Fixtures;
import in.ac.iitm.hostel.duty.workflow.application.DocketRequiredException;
import in.ac.iitm.hostel.duty.workflow.application.GuardFailedException;
import in.ac.iitm.hostel.duty.workflow.application.IllegalTransitionException;
import in.ac.iitm.hostel.duty.workflow.application.ManualAssignmentService;
import in.ac.iitm.hostel.duty.workflow.application.TransitionPayload;
import in.ac.iitm.hostel.duty.workflow.application.WorkflowService;
import in.ac.iitm.hostel.duty.workflow.domain.AssignmentStatus;
import in.ac.iitm.hostel.duty.workflow.domain.Complaint;
import in.ac.iitm.hostel.duty.workflow.domain.ComplaintStatus;
import in.ac.iitm.hostel.duty.workflow.domain.ComplaintTransition;
import in.ac.iitm.hostel.duty.workflow.domain.ComplaintTransitions;
import in.ac.iitm.hostel.duty.workflow.domain.TaskStatus;
import in.ac.iitm.hostel.duty.workflow.domain.TransitionActor;
import in.ac.iitm.hostel.duty.workflow.domain.TransitionGuard;
import in.ac.iitm.hostel.duty.workflow.domain.WorkflowEvent;
import in.ac.iitm.hostel.duty.workflow.infrastructure.ComplaintRepository;
import in.ac.iitm.hostel.duty.workflow.infrastructure.TaskAssignmentRepository;
import in.ac.iitm.hostel.duty.workflow.infrastructure.TaskRepository;
import java.time.Duration;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The guarded transition table in action (section 4.1): the single write path to
 * {@code complaint.status}.
 *
 * <p>The first test walks <strong>every row of the table</strong> rather than the handful the
 * happy path uses, because the value of writing the machine as data is lost the moment a row is
 * unreachable or lands somewhere other than the design says.
 */
@SpringBootTest
class ComplaintStateMachineIntegrationTest extends AbstractIntegrationTest {

    private static final String DOCKET = "EU/2026/00417";
    private static final String ENTITY_TYPE = "complaint";

    @Autowired private WorkflowService workflow;
    @Autowired private ManualAssignmentService assignment;
    @Autowired private ComplaintRepository complaints;
    @Autowired private TaskRepository tasks;
    @Autowired private TaskAssignmentRepository assignments;
    @Autowired private PointLedgerRepository ledger;
    @Autowired private AuditEventRepository auditEvents;
    @Autowired private AppUserRepository users;

    private AppUser manager;
    private AppUser ravi;
    private AppUser kumar;

    static List<ComplaintTransition> everyRow() {
        return ComplaintTransitions.all();
    }

    @BeforeEach
    void provision() {
        manager = users.saveAndFlush(Fixtures.manager());
        ravi = users.saveAndFlush(Fixtures.attender());
        kumar = users.saveAndFlush(Fixtures.attender());
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyRow")
    @DisplayName("every legal row of section 4.2 is accepted and lands where the table says")
    void everyLegalRowIsAccepted(ComplaintTransition row) {
        long complaintId = complaintIn(row.from());
        satisfy(row, complaintId);
        signInAs(row.actor());

        workflow.transition(complaintId, row.event(), payloadFor(row));

        assertThat(complaints.findById(complaintId).orElseThrow().getStatus()).isEqualTo(row.to());
        assertThat(auditOf(complaintId))
                .describedAs("the audit row lands in the same transaction as the change")
                .anySatisfy(
                        event -> {
                            assertThat(event.getEventType()).isEqualTo(row.event().name());
                            assertThat(event.getFromState()).isEqualTo(row.from().name());
                            assertThat(event.getToState()).isEqualTo(row.to().name());
                        });
    }

    @Test
    @DisplayName("a pair the table does not hold is refused, and nothing at all is written")
    void anIllegalPairWritesNothing() {
        long complaintId = complaintIn(ComplaintStatus.ASSIGNED);
        long auditRowsBefore = auditEvents.count();
        Fixtures.signIn(ravi);

        assertThatThrownBy(
                        () ->
                                workflow.transition(
                                        complaintId, WorkflowEvent.MARK_RESOLVED, TransitionPayload.none()))
                .describedAs("a complaint nobody has inspected cannot jump to RESOLVED")
                .isInstanceOf(IllegalTransitionException.class);

        assertThat(complaints.findById(complaintId).orElseThrow().getStatus())
                .isEqualTo(ComplaintStatus.ASSIGNED);
        assertThat(auditEvents.count())
                .describedAs("the table is consulted before any side effect runs")
                .isEqualTo(auditRowsBefore);
        assertThat(ledger.findByAttenderIdOrderByCreatedAtDesc(ravi.getId())).isEmpty();
    }

    @Test
    @DisplayName("the docket gate refuses mark_resolved, and no ledger row appears — T14, E15")
    void theDocketGateRefusesAndCreditsNobody() {
        // Forced into IN_PROGRESS with no docket, which the machine itself cannot produce — T11
        // is the only edge in. That is the point: the guard is belt to the table's braces.
        long complaintId = complaintIn(ComplaintStatus.IN_PROGRESS);
        long auditRowsBefore = auditEvents.count();
        Fixtures.signIn(ravi);

        assertThatThrownBy(
                        () ->
                                workflow.transition(
                                        complaintId, WorkflowEvent.MARK_RESOLVED, TransitionPayload.none()))
                .isInstanceOf(DocketRequiredException.class);

        assertThat(ledger.findByAttenderIdOrderByCreatedAtDesc(ravi.getId()))
                .describedAs("you cannot collect +2 for a complaint you never filed")
                .isEmpty();
        assertThat(complaints.findById(complaintId).orElseThrow().getStatus())
                .isEqualTo(ComplaintStatus.IN_PROGRESS);
        assertThat(auditEvents.count()).isEqualTo(auditRowsBefore);
    }

    @Test
    @DisplayName("a mandatory reason that is missing refuses the move — T5, T8, T10, T12, T17")
    void aMissingReasonIsRefused() {
        long complaintId = complaintIn(ComplaintStatus.INSPECTING);
        Fixtures.signIn(ravi);

        assertThatThrownBy(
                        () ->
                                workflow.transition(
                                        complaintId,
                                        WorkflowEvent.NO_DEFECT_FOUND,
                                        TransitionPayload.reason("   ")))
                .describedAs("closing work without earning has to say why")
                .isInstanceOf(GuardFailedException.class);

        assertThat(complaints.findById(complaintId).orElseThrow().getStatus())
                .isEqualTo(ComplaintStatus.INSPECTING);
    }

    @Test
    @DisplayName("auto_close waits the seven days, then closes — T16")
    void autoCloseWaitsSevenDays() {
        long complaintId = complaintIn(ComplaintStatus.RESOLVED);
        Fixtures.signIn(manager);

        assertThatThrownBy(
                        () ->
                                workflow.transition(
                                        complaintId, WorkflowEvent.AUTO_CLOSE, TransitionPayload.none()))
                .isInstanceOf(GuardFailedException.class);

        resolvedDaysAgo(complaintId, 8);
        workflow.transition(complaintId, WorkflowEvent.AUTO_CLOSE, TransitionPayload.none());

        assertThat(complaints.findById(complaintId).orElseThrow())
                .satisfies(
                        complaint -> {
                            assertThat(complaint.getStatus()).isEqualTo(ComplaintStatus.CLOSED);
                            assertThat(complaint.getClosedAt()).isNotNull();
                        });
    }

    @Test
    @DisplayName("the change, its side effects and its audit row commit together — T14")
    void theChangeItsEffectsAndItsAuditRowCommitTogether() {
        long complaintId = walkToResolvable();
        long taskId = tasks.findByComplaintId(complaintId).orElseThrow().getId();

        Fixtures.signIn(ravi);
        workflow.transition(complaintId, WorkflowEvent.MARK_RESOLVED, TransitionPayload.none());

        assertThat(complaints.findById(complaintId).orElseThrow().getStatus())
                .isEqualTo(ComplaintStatus.RESOLVED);
        assertThat(ledger.balanceOf(ravi.getId(), null)).isEqualTo(2L);
        assertThat(assignments.findByTaskIdAndAttenderId(taskId, ravi.getId()).orElseThrow().getStatus())
                .isEqualTo(AssignmentStatus.COMPLETED);
        assertThat(tasks.findById(taskId).orElseThrow().getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(auditOf(complaintId))
                .filteredOn(event -> "MARK_RESOLVED".equals(event.getEventType()))
                .singleElement()
                .satisfies(event -> assertThat(event.getActorId()).isEqualTo(ravi.getId()));
    }

    /**
     * Ten requests for one move. One of them commits; the rest are told, by the version check or
     * by the table, and roll back whole — no half-credit, no orphan audit row.
     */
    @Test
    @DisplayName("concurrent transitions on one complaint: one wins, the losers write nothing")
    void concurrentTransitionsLeaveOneWinner() throws Exception {
        long complaintId = walkToResolvable();
        int threads = 10;
        CountDownLatch startTogether = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Void>> attempts = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Callable<Void> attempt =
                        () -> {
                            Fixtures.signIn(ravi);
                            startTogether.await(10, TimeUnit.SECONDS);
                            try {
                                workflow.transition(
                                        complaintId, WorkflowEvent.MARK_RESOLVED, TransitionPayload.none());
                            } catch (RuntimeException losersAreExpected) {
                                // The claim is about the database's final state, not who errored.
                            } finally {
                                SecurityContextHolder.clearContext();
                            }
                            return null;
                        };
                attempts.add(pool.submit(attempt));
            }
            startTogether.countDown();
            for (Future<Void> attempt : attempts) {
                attempt.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(complaints.findById(complaintId).orElseThrow().getStatus())
                .isEqualTo(ComplaintStatus.RESOLVED);
        assertThat(ledger.findByAttenderIdOrderByCreatedAtDesc(ravi.getId()))
                .describedAs("+2 for one job, never +20")
                .hasSize(1);
        assertThat(auditOf(complaintId))
                .filteredOn(event -> "MARK_RESOLVED".equals(event.getEventType()))
                .describedAs("one change, therefore exactly one audit row")
                .hasSize(1);
    }

    @Test
    @DisplayName("an attender cannot drive another attender's task — the Actor column, section 11.3")
    void anotherAttendersTaskIsRefused() {
        long complaintId = complaintIn(ComplaintStatus.ASSIGNED);
        Fixtures.signIn(kumar);

        assertThatThrownBy(
                        () ->
                                workflow.transition(
                                        complaintId, WorkflowEvent.ATTENDER_START, TransitionPayload.none()))
                .describedAs("ownership, not role, decides who may move a complaint")
                .isInstanceOf(AccessDeniedException.class);

        assertThat(complaints.findById(complaintId).orElseThrow().getStatus())
                .isEqualTo(ComplaintStatus.ASSIGNED);
    }

    @Test
    @DisplayName("an attender cannot fire a manager's row either — reopen is not theirs")
    void anAttenderCannotFireAManagersRow() {
        long complaintId = complaintIn(ComplaintStatus.RESOLVED);
        Fixtures.signIn(ravi);

        assertThatThrownBy(
                        () ->
                                workflow.transition(
                                        complaintId,
                                        WorkflowEvent.MANAGER_REOPEN,
                                        TransitionPayload.reason("I would like another +2")))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ---------------------------------------------------------------- fixtures

    /**
     * A complaint sitting in one state, with a task and an {@code ACTIVE} assignment for Ravi.
     *
     * <p>{@code NEW} is the exception: assigning is what leaves {@code NEW}, so a complaint that
     * is still there has no task yet — which is also true in production.
     */
    private long complaintIn(ComplaintStatus state) {
        long complaintId = complaints.saveAndFlush(Fixtures.complaint()).getId();
        if (state == ComplaintStatus.NEW) {
            return complaintId;
        }
        Fixtures.signIn(manager);
        assignment.assign(complaintId, ravi.getId());
        force(complaintId, state);
        return complaintId;
    }

    /** Puts a complaint straight into a state, which only a test may do. */
    private void force(long complaintId, ComplaintStatus state) {
        Complaint complaint = complaints.findById(complaintId).orElseThrow();
        if (complaint.getStatus() != state) {
            complaint.moveTo(state, Instant.now());
            complaints.saveAndFlush(complaint);
        }
    }

    private void resolvedDaysAgo(long complaintId, long daysAgo) {
        Complaint complaint = complaints.findById(complaintId).orElseThrow();
        complaint.moveTo(ComplaintStatus.RESOLVED, Instant.now().minus(Duration.ofDays(daysAgo)));
        complaints.saveAndFlush(complaint);
    }

    /** The real walk: assigned, started, defect confirmed, docket filed. */
    private long walkToResolvable() {
        long complaintId = complaintIn(ComplaintStatus.ASSIGNED);
        Fixtures.signIn(ravi);
        workflow.transition(complaintId, WorkflowEvent.ATTENDER_START, TransitionPayload.none());
        workflow.transition(complaintId, WorkflowEvent.DEFECT_CONFIRMED, TransitionPayload.none());
        workflow.transition(complaintId, WorkflowEvent.DOCKET_RECORDED, TransitionPayload.docket(DOCKET));
        return complaintId;
    }

    /** Whatever this row's guard needs that the state alone does not supply. */
    private void satisfy(ComplaintTransition row, long complaintId) {
        if (row.guard() == TransitionGuard.DOCKET_FILED) {
            Complaint complaint = complaints.findById(complaintId).orElseThrow();
            complaint.recordDocket(DOCKET, ravi.getId(), Instant.now());
            complaints.saveAndFlush(complaint);
        }
        if (row.guard() == TransitionGuard.SEVEN_DAYS_SINCE_RESOLUTION) {
            resolvedDaysAgo(complaintId, 8);
        }
    }

    private TransitionPayload payloadFor(ComplaintTransition row) {
        return switch (row.guard()) {
            case DISPATCH_CANDIDATE, TARGET_ATTENDER -> TransitionPayload.attender(ravi.getId());
            // T6 has to move the work, so it names the attender who is not holding it.
            case DIFFERENT_ATTENDER -> TransitionPayload.attender(kumar.getId());
            case REASON -> TransitionPayload.reason("because the design says a reason is mandatory");
            case DOCKET_FORMAT -> TransitionPayload.docket(DOCKET);
            default -> TransitionPayload.none();
        };
    }

    private void signInAs(TransitionActor actor) {
        Fixtures.signIn(actor == TransitionActor.MANAGER || actor == TransitionActor.SYSTEM ? manager : ravi);
    }

    private List<AuditEvent> auditOf(long complaintId) {
        return auditEvents.findByEntityTypeAndEntityIdOrderByOccurredAtAsc(ENTITY_TYPE, complaintId);
    }
}
