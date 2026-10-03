package in.ac.iitm.hostel.duty.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import in.ac.iitm.hostel.duty.identity.domain.AppUser;
import in.ac.iitm.hostel.duty.identity.infrastructure.AppUserRepository;
import in.ac.iitm.hostel.duty.ledger.application.LeaderboardRow;
import in.ac.iitm.hostel.duty.ledger.application.LeaderboardService;
import in.ac.iitm.hostel.duty.ledger.domain.PointLedgerEntry;
import in.ac.iitm.hostel.duty.ledger.domain.ReasonCode;
import in.ac.iitm.hostel.duty.ledger.infrastructure.PointLedgerRepository;
import in.ac.iitm.hostel.duty.support.AbstractIntegrationTest;
import in.ac.iitm.hostel.duty.support.Fixtures;
import in.ac.iitm.hostel.duty.workflow.application.ManualAssignmentService;
import in.ac.iitm.hostel.duty.workflow.application.ResolvedTask;
import in.ac.iitm.hostel.duty.workflow.application.TaskResolutionService;
import in.ac.iitm.hostel.duty.workflow.application.TransitionPayload;
import in.ac.iitm.hostel.duty.workflow.application.WorkflowService;
import in.ac.iitm.hostel.duty.workflow.domain.AssignmentStatus;
import in.ac.iitm.hostel.duty.workflow.domain.ComplaintStatus;
import in.ac.iitm.hostel.duty.workflow.domain.WorkflowEvent;
import in.ac.iitm.hostel.duty.workflow.infrastructure.ComplaintRepository;
import in.ac.iitm.hostel.duty.workflow.infrastructure.TaskAssignmentRepository;
import in.ac.iitm.hostel.duty.workflow.infrastructure.TaskRepository;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Transition T17 — the reopen, and why the ledger survives it.
 *
 * <p>The ledger is append-only, so a reopen cannot delete the +2. It writes a compensating
 * {@code REVERSAL} row instead: the balance falls by two and <em>both</em> rows stay in the
 * attender's history, which is what settles a bonus dispute three months later (section 11.7).
 *
 * <p>The sharper claim is the one about the key. A re-resolution after a reopen is genuine repeat
 * work and must be credited again — but the credit's key is derived from the work, so without the
 * {@code :r{n}} suffix of section 7.3 it would present the very key that was just reversed, be
 * recognised as a replay, and silently credit nothing.
 */
@SpringBootTest
class ReopenReversesTheCreditIntegrationTest extends AbstractIntegrationTest {

    private static final String DOCKET = "EU/2026/00417";

    @Autowired private WorkflowService workflow;
    @Autowired private TaskResolutionService resolution;
    @Autowired private ManualAssignmentService assignment;
    @Autowired private ComplaintRepository complaints;
    @Autowired private TaskRepository tasks;
    @Autowired private TaskAssignmentRepository assignments;
    @Autowired private PointLedgerRepository ledger;
    @Autowired private LeaderboardService leaderboard;
    @Autowired private AppUserRepository users;

    private AppUser manager;
    private AppUser ravi;
    private long complaintId;
    private long taskId;

    @BeforeEach
    void resolveSomeWork() {
        manager = users.saveAndFlush(Fixtures.manager());
        ravi = users.saveAndFlush(Fixtures.attender());

        complaintId = complaints.saveAndFlush(Fixtures.complaintInTriage()).getId();
        Fixtures.signIn(manager);
        assignment.assign(complaintId, ravi.getId());
        taskId = tasks.findByComplaintId(complaintId).orElseThrow().getId();

        Fixtures.signIn(ravi);
        workflow.transition(complaintId, WorkflowEvent.ATTENDER_START, TransitionPayload.none());
        workflow.transition(complaintId, WorkflowEvent.DEFECT_CONFIRMED, TransitionPayload.none());
        resolution.recordDocket(taskId, DOCKET);
        resolution.resolve(taskId);
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private void reopen() {
        Fixtures.signIn(manager);
        workflow.transition(
                complaintId,
                WorkflowEvent.MANAGER_REOPEN,
                TransitionPayload.reason("the tap is still dripping"));
    }

    private List<PointLedgerEntry> historyOfRavi() {
        return ledger.findByAttenderIdOrderByCreatedAtDesc(ravi.getId());
    }

    @Test
    @DisplayName("a reopen writes a REVERSAL and nets to zero — the credit is never deleted")
    void reopenWritesACompensatingRow() {
        assertThat(ledger.balanceOf(ravi.getId(), null)).isEqualTo(2L);
        Long creditId = historyOfRavi().get(0).getId();

        reopen();

        assertThat(historyOfRavi())
                .describedAs("two immutable facts, not one edited row")
                .hasSize(2)
                .anySatisfy(
                        entry -> {
                            assertThat(entry.getReasonCode()).isEqualTo(ReasonCode.COMPLAINT_RESOLVED);
                            assertThat(entry.getPoints()).isEqualTo(2);
                        })
                .anySatisfy(
                        entry -> {
                            assertThat(entry.getReasonCode()).isEqualTo(ReasonCode.REVERSAL);
                            assertThat(entry.getPoints()).isEqualTo(-2);
                            assertThat(entry.getReversesId()).isEqualTo(creditId);
                            assertThat(entry.getIdempotencyKey()).isEqualTo("REV:" + creditId);
                        });
        assertThat(ledger.balanceOf(ravi.getId(), null)).isZero();
    }

    @Test
    @DisplayName("a reopen puts the complaint and the work back where they can be finished")
    void reopenReturnsTheWork() {
        reopen();

        assertThat(complaints.findById(complaintId).orElseThrow())
                .satisfies(
                        complaint -> {
                            assertThat(complaint.getStatus()).isEqualTo(ComplaintStatus.IN_PROGRESS);
                            assertThat(complaint.getReopenCount()).isEqualTo(1);
                            assertThat(complaint.getResolvedAt())
                                    .describedAs(
                                            "the 7-day PII window must not expire while the work is"
                                                    + " live again — section 11.4")
                                    .isNull();
                            assertThat(complaint.getDocketNo())
                                    .describedAs("the complaint is still filed with the Engineering Unit")
                                    .isEqualTo(DOCKET);
                        });
        assertThat(assignments.findByTaskIdAndAttenderId(taskId, ravi.getId()).orElseThrow().getStatus())
                .describedAs("somebody has to be able to redo it")
                .isEqualTo(AssignmentStatus.ACTIVE);
    }

    @Test
    @DisplayName("re-resolving after a reopen credits again, under a new key — :r1, section 7.3")
    void reResolvingCreditsAgainUnderANewKey() {
        reopen();

        Fixtures.signIn(ravi);
        ResolvedTask again = resolution.resolve(taskId);

        assertThat(again.alreadyApplied())
                .describedAs("genuine repeat work must not be mistaken for a retried tap")
                .isFalse();
        assertThat(again.pointsAwarded()).isEqualTo(2);
        assertThat(ledger.balanceOf(ravi.getId(), null))
                .describedAs("+2, reversed, then earned again: the net is +2")
                .isEqualTo(2L);
        assertThat(historyOfRavi())
                .hasSize(3)
                .extracting(PointLedgerEntry::getIdempotencyKey)
                .contains("RESOLVE:" + taskId + ":" + ravi.getId() + ":r1");
    }

    @Test
    @DisplayName("the whole cycle nets correctly on the manager's leaderboard")
    void theLeaderboardFollowsTheCycle() {
        reopen();
        Fixtures.signIn(ravi);
        resolution.resolve(taskId);

        Fixtures.signIn(manager);
        assertThat(leaderboard.currentStandings().rows())
                .filteredOn(row -> row.attenderId() == ravi.getId())
                .singleElement()
                .extracting(LeaderboardRow::total)
                .describedAs("a SUM over three immutable rows, computed at read time — 7.8")
                .isEqualTo(2L);
    }

    @Test
    @DisplayName("a second reopen of the same resolution is a no-op, not a double reversal")
    void reversalIsItselfIdempotent() {
        reopen();
        Fixtures.signIn(ravi);
        resolution.resolve(taskId);

        reopen();

        assertThat(ledger.balanceOf(ravi.getId(), null))
                .describedAs("+2, −2, +2, −2 — each reversal keyed to the one credit it undoes")
                .isZero();
        assertThat(historyOfRavi()).hasSize(4);
    }
}
