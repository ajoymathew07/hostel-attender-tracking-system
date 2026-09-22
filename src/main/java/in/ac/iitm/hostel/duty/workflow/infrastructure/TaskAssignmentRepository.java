package in.ac.iitm.hostel.duty.workflow.infrastructure;

import in.ac.iitm.hostel.duty.workflow.domain.AssignmentStatus;
import in.ac.iitm.hostel.duty.workflow.domain.TaskAssignment;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Assignments (data model section 3.6).
 *
 * <p>{@code uq_task_attender} means at most one row per (task, attender), so the lookup below is
 * naturally single-valued.
 */
public interface TaskAssignmentRepository extends JpaRepository<TaskAssignment, Long> {

    Optional<TaskAssignment> findByTaskIdAndAttenderId(Long taskId, Long attenderId);

    List<TaskAssignment> findByTaskIdAndStatus(Long taskId, AssignmentStatus status);

    List<TaskAssignment> findByAttenderIdAndStatus(Long attenderId, AssignmentStatus status);

    long countByTaskIdAndStatus(Long taskId, AssignmentStatus status);
}
