package duty.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import duty.identity.domain.AppUser;
import duty.identity.infrastructure.AppUserRepository;
import duty.support.AbstractIntegrationTest;
import duty.support.Fixtures;
import duty.workflow.application.ManualAssignmentService;
import duty.workflow.domain.AssignMethod;
import duty.workflow.domain.AssignmentStatus;
import duty.workflow.domain.TaskAssignment;
import duty.workflow.domain.TaskType;
import duty.workflow.infrastructure.ComplaintRepository;
import duty.workflow.infrastructure.TaskAssignmentRepository;
import duty.workflow.infrastructure.TaskRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Manual assignment: assign, reassign, release, and who may do it (sections 10.3, 11.3).
 *
 * <p>The authorization half is asserted at the <em>service</em> layer rather than through a URL,
 * because that is where section 11.3 puts it — a check that only a controller performs is one new
 * caller away from being absent.
 */
@SpringBootTest
class ManualAssignmentIntegrationTest extends AbstractIntegrationTest {

    @Autowired private ManualAssignmentService assignment;
    @Autowired private TaskRepository tasks;
    @Autowired private TaskAssignmentRepository assignments;
    @Autowired private ComplaintRepository complaints;
    @Autowired private AppUserRepository users;

    private AppUser manager;
    private AppUser ravi;
    private AppUser kumar;
    private long complaintId;

    @BeforeEach
    void provision() {
        manager = users.saveAndFlush(Fixtures.manager());
        ravi = users.saveAndFlush(Fixtures.attender());
        kumar = users.saveAndFlush(Fixtures.attender());
        complaintId = complaints.saveAndFlush(Fixtures.complaint()).getId();
        Fixtures.signIn(manager);
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private Optional<TaskAssignment> assignmentOf(AppUser attender) {
        return tasks.findByComplaintId(complaintId)
                .flatMap(task -> assignments.findByTaskIdAndAttenderId(task.getId(), attender.getId()));
    }

    private List<TaskAssignment> live() {
        return tasks.findByComplaintId(complaintId)
                .map(task -> assignments.findByTaskIdAndStatus(task.getId(), AssignmentStatus.ACTIVE))
                .orElseGet(List::of);
    }

    @Test
    @DisplayName("assigning a complaint creates its task and hands it to the attender — T4")
    void assigningCreatesTheTaskAndTheAssignment() {
        assertThat(tasks.findByComplaintId(complaintId))
                .describedAs("nothing to hold before anyone is assigned")
                .isEmpty();

        assignment.assign(complaintId, ravi.getId());

        assertThat(tasks.findByComplaintId(complaintId))
                .get()
                .satisfies(
                        task -> {
                            assertThat(task.getTaskType()).isEqualTo(TaskType.COMPLAINT);
                            assertThat(task.getPointValue()).isEqualTo(2);
                            assertThat(task.getCreatedBy()).isEqualTo(manager.getId());
                        });
        assertThat(assignmentOf(ravi))
                .get()
                .satisfies(
                        held -> {
                            assertThat(held.getStatus()).isEqualTo(AssignmentStatus.ACTIVE);
                            assertThat(held.getAssignMethod()).isEqualTo(AssignMethod.MANUAL);
                            assertThat(held.getAssignedBy()).isEqualTo(manager.getId());
                        });
    }

    @Test
    @DisplayName("reassigning moves the work: the old holder is released, the new one is active — T6")
    void reassigningMovesTheWork() {
        assignment.assign(complaintId, ravi.getId());
        assignment.assign(complaintId, kumar.getId());

        assertThat(assignmentOf(ravi)).get().extracting(TaskAssignment::getStatus).isEqualTo(AssignmentStatus.RELEASED);
        assertThat(assignmentOf(kumar)).get().extracting(TaskAssignment::getStatus).isEqualTo(AssignmentStatus.ACTIVE);
        assertThat(live())
                .describedAs("an unshared task has exactly one holder at a time")
                .extracting(TaskAssignment::getAttenderId)
                .containsExactly(kumar.getId());
    }

    @Test
    @DisplayName("assigning the same attender twice is not a second row and does not release them")
    void reassigningToTheSameAttenderIsIdempotent() {
        assignment.assign(complaintId, ravi.getId());
        assignment.assign(complaintId, ravi.getId());

        assertThat(live()).extracting(TaskAssignment::getAttenderId).containsExactly(ravi.getId());
    }

    @Test
    @DisplayName("releasing hands the work back to the pool")
    void releasingEmptiesTheTask() {
        assignment.assign(complaintId, ravi.getId());

        assignment.release(complaintId);

        assertThat(live()).isEmpty();
        assertThat(assignmentOf(ravi)).get().extracting(TaskAssignment::getStatus).isEqualTo(AssignmentStatus.RELEASED);
    }

    @Test
    @DisplayName("a released attender can be given the work back — uq_task_attender allows one row")
    void aReleasedAttenderCanBeAssignedAgain() {
        assignment.assign(complaintId, ravi.getId());
        assignment.release(complaintId);

        assignment.assign(complaintId, ravi.getId());

        assertThat(live()).extracting(TaskAssignment::getAttenderId).containsExactly(ravi.getId());
    }

    @Test
    @DisplayName("an attender cannot assign, reassign or release — section 11.3, at the service layer")
    void anAttenderMayNotAssign() {
        long raviId = ravi.getId();
        Fixtures.signIn(ravi);

        assertThatThrownBy(() -> assignment.assign(complaintId, raviId))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> assignment.release(complaintId)).isInstanceOf(AccessDeniedException.class);

        assertThat(tasks.findByComplaintId(complaintId))
                .describedAs("the refusal happened before anything was written")
                .isEmpty();
    }
}
