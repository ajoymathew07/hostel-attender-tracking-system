package duty.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import duty.audit.domain.AuditEvent;
import duty.audit.infrastructure.AuditEventRepository;
import duty.identity.domain.AppUser;
import duty.identity.infrastructure.AppUserRepository;
import duty.support.AbstractIntegrationTest;
import duty.support.Fixtures;
import duty.workflow.application.ManualAssignmentService;
import duty.workflow.application.WorkflowService.TransitionPayload;
import duty.workflow.application.WorkflowService;
import duty.workflow.domain.Complaint.ComplaintStatus;
import duty.workflow.domain.Complaint;
import duty.workflow.domain.ComplaintTransitions.WorkflowEvent;
import duty.workflow.infrastructure.ComplaintRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The SLA clock pauses while work is blocked and the due date moves by the hold time. The complaint is
 * walked through real transitions.
 */
@SpringBootTest
class SlaHoldIntegrationTest extends AbstractIntegrationTest {

    private static final String DOCKET = "EU/2026/00417";
    private static final Instant DUE_AT = Instant.parse("2026-05-02T06:00:00Z");
    private static final Duration ONE_DAY = Duration.ofDays(1);

    @Autowired private WorkflowService workflow;
    @Autowired private ManualAssignmentService assignment;
    @Autowired private ComplaintRepository complaints;
    @Autowired private AuditEventRepository auditEvents;
    @Autowired private AppUserRepository users;

    private AppUser manager;
    private AppUser ravi;

    @BeforeEach
    void provision() {
        manager = users.saveAndFlush(Fixtures.manager());
        ravi = users.saveAndFlush(Fixtures.attender());
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("a complaint held for a day has its due date pushed by a day — T12, T13")
    void holdTimeIsExcludedFromTheSla() {
        long complaintId = inProgressWithADueDate();

        workflow.transition(
                complaintId, WorkflowEvent.BLOCK, TransitionPayload.reason("waiting on the Engineering Unit"));

        Complaint held = complaints.findById(complaintId).orElseThrow();
        assertThat(held.getStatus()).isEqualTo(ComplaintStatus.ON_HOLD);
        assertThat(held.getHoldStartedAt()).describedAs("the clock is paused").isNotNull();
        assertThat(held.getSlaDueAt())
                .describedAs("the pause alone moves nothing; the extension is known only on resume")
                .isEqualTo(DUE_AT);

        // The hold began a day ago. Rewriting the stamp is how a test buys a day without waiting
        // one; every other step here is the real transition.
        heldSince(complaintId, Instant.now().minus(ONE_DAY));

        workflow.transition(complaintId, WorkflowEvent.UNBLOCK, TransitionPayload.none());

        Complaint resumed = complaints.findById(complaintId).orElseThrow();
        assertThat(resumed.getStatus()).isEqualTo(ComplaintStatus.IN_PROGRESS);
        assertThat(resumed.getHoldStartedAt()).describedAs("the clock is running again").isNull();
        assertThat(resumed.getSlaDueAt())
                .describedAs("a day on hold is a day later, not a day of the attender's window")
                .isCloseTo(DUE_AT.plus(ONE_DAY), within(1, ChronoUnit.MINUTES));

        // The individual hold span lives in the log rather than in a column of its own: a complaint
        // can be blocked more than once and `sla_due_at` carries the accumulated effect (ADR-012).
        assertThat(auditEvents.findByEntityTypeAndEntityIdOrderByOccurredAtAsc("complaint", complaintId))
                .filteredOn(event -> "UNBLOCK".equals(event.getEventType()))
                .singleElement()
                .satisfies(event -> assertThat(event.getDetail()).contains("heldSince"));
    }

    @Test
    @DisplayName("a second hold extends the due date again, from where the first left it")
    void aSecondHoldExtendsItAgain() {
        long complaintId = inProgressWithADueDate();

        blockForADay(complaintId);
        Instant afterTheFirstHold = complaints.findById(complaintId).orElseThrow().getSlaDueAt();

        blockForADay(complaintId);

        assertThat(complaints.findById(complaintId).orElseThrow().getSlaDueAt())
                .describedAs("each wait is excluded, not only the first")
                .isCloseTo(afterTheFirstHold.plus(ONE_DAY), within(1, ChronoUnit.MINUTES));
    }

    /** The real walk: assigned, started, defect confirmed, docket filed — then a due date. */
    private long inProgressWithADueDate() {
        long complaintId = complaints.saveAndFlush(Fixtures.complaintInTriage()).getId();
        Fixtures.signIn(manager);
        assignment.assign(complaintId, ravi.getId());

        Fixtures.signIn(ravi);
        workflow.transition(complaintId, WorkflowEvent.ATTENDER_START, TransitionPayload.none());
        workflow.transition(complaintId, WorkflowEvent.DEFECT_CONFIRMED, TransitionPayload.none());
        workflow.transition(complaintId, WorkflowEvent.DOCKET_RECORDED, TransitionPayload.docket(DOCKET));

        Complaint complaint = complaints.findById(complaintId).orElseThrow();
        complaint.scheduleSla(DUE_AT);
        complaints.saveAndFlush(complaint);
        return complaintId;
    }

    private void blockForADay(long complaintId) {
        workflow.transition(complaintId, WorkflowEvent.BLOCK, TransitionPayload.reason("no parts"));
        heldSince(complaintId, Instant.now().minus(ONE_DAY));
        workflow.transition(complaintId, WorkflowEvent.UNBLOCK, TransitionPayload.none());
    }

    private void heldSince(long complaintId, Instant when) {
        Complaint complaint = complaints.findById(complaintId).orElseThrow();
        complaint.pauseSlaClock(when);
        complaints.saveAndFlush(complaint);
    }
}
