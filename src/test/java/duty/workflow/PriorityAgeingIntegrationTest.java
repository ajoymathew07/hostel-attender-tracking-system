package duty.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import duty.audit.domain.AuditEvent;
import duty.audit.infrastructure.AuditEventRepository;
import duty.support.AbstractIntegrationTest;
import duty.support.Fixtures;
import duty.workflow.application.ComplaintListItem;
import duty.workflow.application.ComplaintQueryService;
import duty.workflow.application.PriorityService;
import duty.workflow.domain.ClassificationMethod;
import duty.workflow.domain.Complaint;
import duty.workflow.domain.ComplaintDomain;
import duty.workflow.domain.ComplaintStatus;
import duty.workflow.domain.Priority;
import duty.workflow.infrastructure.ComplaintRepository;
import duty.workflow.infrastructure.PriorityAgeingScheduler;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;

/**
 * The aging job of section 4.5: old low-priority work surfaces above new noise.
 *
 * <p>Time is controlled by choosing {@code sla_due_at}, not by mocking a clock. A complaint whose
 * due date is ten days in the past is past 1.5× any window in section 8.2's table, which is exactly
 * the condition the job is defined by — and it is the condition production will meet too.
 *
 * <p>Every complaint here is {@code ELECTRICAL} with base {@code P3}, so the window is 72 working
 * hours and the escalation threshold is half a window past the due date.
 */
@SpringBootTest
@WithMockUser(roles = "MANAGER")
class PriorityAgeingIntegrationTest extends AbstractIntegrationTest {

    private static final String LOCK_NAME = "priorityAgeing";

    @Autowired private PriorityService priorities;
    @Autowired private PriorityAgeingScheduler scheduler;
    @Autowired private ComplaintRepository complaints;
    @Autowired private ComplaintQueryService queries;
    @Autowired private AuditEventRepository auditEvents;
    @Autowired private JdbcTemplate jdbc;

    @Test
    @DisplayName("past 1.5x its window a P3 escalates, and base_priority is kept")
    void pastOneAndAHalfWindowsItEscalates() {
        Complaint complaint = overdueBy(Duration.ofDays(10), Priority.P3, ComplaintStatus.ASSIGNED);

        priorities.ageOpenComplaints();

        Complaint aged = reload(complaint);
        assertThat(aged.getPriority()).isEqualTo(Priority.P2);
        assertThat(aged.getBasePriority())
                .describedAs("kept so the dashboard can show \"P2 (aged from P3)\"")
                .isEqualTo(Priority.P3);
        assertThat(aged.isSlaBreached()).isTrue();

        assertThat(historyOf(aged))
                .anySatisfy(
                        event -> {
                            assertThat(event.getEventType()).isEqualTo("PRIORITY_AGED");
                            assertThat(event.getFromState()).isEqualTo("P3");
                            assertThat(event.getToState()).isEqualTo("P2");
                        });
    }

    @Test
    @DisplayName("escalation is capped at P1, however long the complaint stays open")
    void escalationStopsAtP1() {
        Complaint complaint = overdueBy(Duration.ofDays(30), Priority.P3, ComplaintStatus.ASSIGNED);

        priorities.ageOpenComplaints();
        assertThat(reload(complaint).getPriority()).isEqualTo(Priority.P2);

        priorities.ageOpenComplaints();
        assertThat(reload(complaint).getPriority()).isEqualTo(Priority.P1);

        priorities.ageOpenComplaints();
        assertThat(reload(complaint).getPriority())
                .describedAs("section 4.5 caps aging at P1; there is nothing above it")
                .isEqualTo(Priority.P1);
        assertThat(reload(complaint).getBasePriority()).isEqualTo(Priority.P3);
    }

    @Test
    @DisplayName("a complaint that has reached RESOLVED is not escalated")
    void resolvedWorkIsNotEscalated() {
        Complaint complaint = overdueBy(Duration.ofDays(10), Priority.P3, ComplaintStatus.RESOLVED);

        priorities.ageOpenComplaints();

        Complaint untouched = reload(complaint);
        assertThat(untouched.getPriority())
                .describedAs("section 4.5 ages only work that has not reached RESOLVED")
                .isEqualTo(Priority.P3);
        assertThat(untouched.isSlaBreached()).isFalse();
    }

    @Test
    @DisplayName("a complaint on hold is not escalated — hold time does not count toward aging")
    void heldWorkIsNotEscalated() {
        Complaint complaint = overdueBy(Duration.ofDays(10), Priority.P3, ComplaintStatus.ON_HOLD);

        priorities.ageOpenComplaints();

        assertThat(reload(complaint).getPriority())
                .describedAs("waiting on the Engineering Unit is not attender slowness (T12)")
                .isEqualTo(Priority.P3);
    }

    @Test
    @DisplayName("merely past its due date is not yet past 1.5x, so it is flagged and not escalated")
    void justOverdueIsFlaggedButNotEscalated() {
        Complaint complaint = overdueBy(Duration.ofMinutes(5), Priority.P3, ComplaintStatus.ASSIGNED);

        priorities.ageOpenComplaints();

        Complaint swept = reload(complaint);
        assertThat(swept.getPriority())
                .describedAs("the escalation is at 1.5x the window, not at the due date")
                .isEqualTo(Priority.P3);
        assertThat(swept.isSlaBreached()).isTrue();
    }

    @Test
    @DisplayName("the manager's list shows the aged priority, what it aged from, and that it is overdue")
    void theListExplainsTheEscalation() {
        Complaint complaint = overdueBy(Duration.ofDays(10), Priority.P3, ComplaintStatus.ASSIGNED);

        priorities.ageOpenComplaints();

        ComplaintListItem row =
                queries.listAll().stream()
                        .filter(item -> item.id() == complaint.getId())
                        .findFirst()
                        .orElseThrow();
        assertThat(row.priority()).isEqualTo(Priority.P2);
        assertThat(row.basePriority()).isEqualTo(Priority.P3);
        assertThat(row.aged()).isTrue();
        assertThat(row.slaOverdue()).isTrue();
    }

    @Test
    @DisplayName("the sweep cannot run while another instance holds the lock — no double escalation")
    void aSweepHeldByAnotherInstanceDoesNotRun() {
        Complaint complaint = overdueBy(Duration.ofDays(10), Priority.P3, ComplaintStatus.ASSIGNED);

        try {
            // Take the lock once so the row exists, then hold it into the future as a second
            // instance would. ShedLock's own run escalates this complaint's first step.
            scheduler.age();
            assertThat(reload(complaint).getPriority()).isEqualTo(Priority.P2);

            jdbc.update(
                    "UPDATE shedlock SET lock_until = now() + interval '1 hour', locked_at = now(),"
                            + " locked_by = 'another-instance' WHERE name = ?",
                    LOCK_NAME);

            scheduler.age();

            assertThat(reload(complaint).getPriority())
                    .describedAs("two instances must not each apply a step and take P3 to P1 in one tick")
                    .isEqualTo(Priority.P2);
        } finally {
            // Hand the lock back rather than deleting the row: ShedLock remembers that the row
            // exists and only ever updates it again, so removing it would wedge every later sweep.
            jdbc.update("UPDATE shedlock SET lock_until = now() WHERE name = ?", LOCK_NAME);
        }
    }

    /** An ELECTRICAL complaint whose SLA ran out {@code ago} in the past. */
    private Complaint overdueBy(Duration ago, Priority basePriority, ComplaintStatus status) {
        Instant reportedAt = Instant.now().minus(ago).minus(Duration.ofDays(3));
        Complaint complaint =
                new Complaint(
                        Fixtures.unique("CAU-2026"),
                        "Tube light flickering in the common room",
                        reportedAt,
                        ComplaintDomain.ELECTRICAL,
                        ClassificationMethod.REPORTED,
                        basePriority,
                        reportedAt);
        complaint.scheduleSla(Instant.now().minus(ago));
        complaint.moveTo(status, Instant.now());
        return complaints.saveAndFlush(complaint);
    }

    private Complaint reload(Complaint complaint) {
        return complaints.findById(complaint.getId()).orElseThrow();
    }

    private List<AuditEvent> historyOf(Complaint complaint) {
        return auditEvents.findByEntityTypeAndEntityIdOrderByOccurredAtAsc("complaint", complaint.getId());
    }
}
