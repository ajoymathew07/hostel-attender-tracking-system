package duty.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import duty.identity.application.AccessGuard;
import duty.identity.domain.AppUser;
import duty.identity.infrastructure.AppUserRepository;
import duty.support.AbstractIntegrationTest;
import duty.support.Fixtures;
import duty.workflow.application.AttenderQueueService;
import duty.workflow.application.ComplaintListItem;
import duty.workflow.application.ComplaintQueryService;
import duty.workflow.application.ManualAssignmentService;
import duty.workflow.application.QueuedTask;
import duty.workflow.domain.Complaint;
import duty.workflow.domain.ComplaintStatus;
import duty.workflow.infrastructure.ComplaintRepository;
import duty.workflow.infrastructure.TaskRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;

/** The attender's queue, and the 7-day window for student details. All values here are made up. */
@SpringBootTest
class AttenderQueueIntegrationTest extends AbstractIntegrationTest {

    private static final String NAME = "Synthetic Resident";
    private static final String ROLL_NO = "CE21B451";
    private static final String PHONE = "9000000451";

    @Autowired private AttenderQueueService queue;
    @Autowired private ManualAssignmentService assignment;
    @Autowired private ComplaintQueryService managerView;
    @Autowired private ComplaintRepository complaints;
    @Autowired private TaskRepository tasks;
    @Autowired private AppUserRepository users;
    @Autowired private AccessGuard access;

    private AppUser manager;
    private AppUser ravi;
    private AppUser kumar;
    private long complaintId;

    @BeforeEach
    void provision() {
        manager = users.saveAndFlush(Fixtures.manager());
        ravi = users.saveAndFlush(Fixtures.attender());
        kumar = users.saveAndFlush(Fixtures.attender());

        Complaint complaint = Fixtures.complaintInTriage();
        complaint.recordReporter(NAME, ROLL_NO, PHONE, "resident@example.com", "0214");
        complaintId = complaints.saveAndFlush(complaint).getId();

        Fixtures.signIn(manager);
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    /** Marks the complaint resolved {@code daysAgo} days ago. */
    private void resolvedDaysAgo(long daysAgo) {
        Complaint complaint = complaints.findById(complaintId).orElseThrow();
        complaint.moveTo(ComplaintStatus.RESOLVED, Instant.now().minus(Duration.ofDays(daysAgo)));
        complaints.saveAndFlush(complaint);
    }

    private long taskId() {
        return tasks.findByComplaintId(complaintId).orElseThrow().getId();
    }

    @Test
    @DisplayName("an attender sees their own assignment and nobody else's")
    void theQueueIsScopedToTheSignedInAttender() {
        assignment.assign(complaintId, ravi.getId());

        Fixtures.signIn(ravi);
        assertThat(queue.myQueue())
                .singleElement()
                .extracting(QueuedTask::taskId)
                .isEqualTo(taskId());

        Fixtures.signIn(kumar);
        assertThat(queue.myQueue())
                .describedAs("another attender's work is not in this attender's queue — E20")
                .isEmpty();
    }

    @Test
    @DisplayName("reassigning moves the queue entry from one attender to the other — T6")
    void reassigningMovesTheQueueEntry() {
        assignment.assign(complaintId, ravi.getId());
        assignment.assign(complaintId, kumar.getId());

        Fixtures.signIn(ravi);
        assertThat(queue.myQueue()).isEmpty();

        Fixtures.signIn(kumar);
        assertThat(queue.myQueue()).singleElement().extracting(QueuedTask::taskId).isEqualTo(taskId());
    }

    @Test
    @DisplayName("the assigned attender sees the student's name, roll number and phone while the work is live")
    void theAssignedAttenderSeesTheStudent() {
        assignment.assign(complaintId, ravi.getId());

        Fixtures.signIn(ravi);
        assertThat(queue.myQueue())
                .singleElement()
                .satisfies(
                        card -> {
                            assertThat(card.studentName()).isEqualTo(NAME);
                            assertThat(card.studentRollNo()).isEqualTo(ROLL_NO);
                            assertThat(card.studentPhone()).isEqualTo(PHONE);
                            assertThat(card.hasStudentContact()).isTrue();
                        });
    }

    @Test
    @DisplayName("six days after resolution they are still there — the window has not closed yet")
    void theWindowIsStillOpenOnDaySix() {
        assignment.assign(complaintId, ravi.getId());
        resolvedDaysAgo(6);

        Fixtures.signIn(ravi);
        assertThat(queue.myQueue())
                .singleElement()
                .extracting(QueuedTask::studentPhone)
                .isEqualTo(PHONE);
    }

    @Test
    @DisplayName("eight days after resolution they are gone from the attender's view — section 11.4")
    void theWindowClosesSevenDaysAfterResolution() {
        assignment.assign(complaintId, ravi.getId());
        resolvedDaysAgo(8);

        Fixtures.signIn(ravi);
        assertThat(queue.myQueue())
                .singleElement()
                .satisfies(
                        card -> {
                            assertThat(card.studentName()).isNull();
                            assertThat(card.studentRollNo()).isNull();
                            assertThat(card.studentPhone()).isNull();
                            assertThat(card.hasStudentContact()).isFalse();
                            assertThat(card.room())
                                    .describedAs("the room is operational data, not the window's subject")
                                    .isEqualTo("0214");
                        });
    }

    @Test
    @DisplayName("the manager still sees them after the window closes — scoping, not deletion")
    void theManagerKeepsTheRecord() {
        assignment.assign(complaintId, ravi.getId());
        resolvedDaysAgo(8);

        List<ComplaintListItem> listed = managerView.listAll();

        assertThat(listed)
                .filteredOn(item -> item.id() == complaintId)
                .singleElement()
                .satisfies(
                        item -> {
                            assertThat(item.reporterName()).isEqualTo(NAME);
                            assertThat(item.reporterRollNo()).isEqualTo(ROLL_NO);
                            assertThat(item.reporterPhone()).isEqualTo(PHONE);
                            assertThat(item.assigneeId())
                                    .describedAs("and still sees who holds the work")
                                    .isEqualTo(ravi.getId());
                        });
    }

    @Test
    @DisplayName("a queue card's toString carries none of the resident's details — section 11.4")
    void queuedTaskToStringIsSafe() {
        assignment.assign(complaintId, ravi.getId());

        Fixtures.signIn(ravi);
        assertThat(queue.myQueue())
                .singleElement()
                .satisfies(card -> assertThat(card.toString()).doesNotContain(NAME, ROLL_NO, PHONE));
    }

    @Test
    @DisplayName("@access.ownsTask now passes for the assignee and still denies everyone else")
    void ownershipIsWiredUpAtLast() {
        assignment.assign(complaintId, ravi.getId());
        long taskId = taskId();

        Fixtures.signIn(ravi);
        assertThat(access.ownsTask(taskId))
                .describedAs("the workflow module finally supplies the lookup identity declared")
                .isTrue();
        assertThat(queue.myTask(taskId)).isPresent();

        Fixtures.signIn(kumar);
        assertThat(access.ownsTask(taskId)).isFalse();

        Fixtures.signIn(manager);
        assertThat(access.ownsTask(taskId))
                .describedAs("role is never a way round ownership — section 11.3")
                .isFalse();

        assignment.release(complaintId);
        Fixtures.signIn(ravi);
        assertThat(access.ownsTask(taskId))
                .describedAs("a RELEASED row is exactly who must no longer act on the task")
                .isFalse();
    }
}
