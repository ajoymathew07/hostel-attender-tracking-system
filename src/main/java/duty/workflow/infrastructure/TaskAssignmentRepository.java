package duty.workflow.infrastructure;

import duty.workflow.domain.AssignmentStatus;
import duty.workflow.domain.TaskAssignment;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Task assignments. At most one row per task and attender. */
public interface TaskAssignmentRepository extends JpaRepository<TaskAssignment, Long> {

    Optional<TaskAssignment> findByTaskIdAndAttenderId(Long taskId, Long attenderId);

    List<TaskAssignment> findByTaskIdAndStatus(Long taskId, AssignmentStatus status);

    List<TaskAssignment> findByAttenderIdAndStatus(Long attenderId, AssignmentStatus status);

    long countByTaskIdAndStatus(Long taskId, AssignmentStatus status);

    /** How many assignments this attender got since {@code from}: a dispatch tie-break. */
    long countByAttenderIdAndAssignedAtGreaterThanEqual(Long attenderId, Instant from);

    /** Their most recent assignment: a dispatch tie-break. */
    Optional<TaskAssignment> findFirstByAttenderIdOrderByAssignedAtDesc(Long attenderId);

    /**
     * One attender's queue with each complaint loaded in the same query, so there is no query per card.
     * Special duties are excluded because they have no complaint.
     */
    @Query(
            """
            select a from TaskAssignment a
              join fetch a.task t
              join fetch t.complaint c
             where a.attenderId = :attenderId
               and a.status = :status
             order by c.priority asc, a.assignedAt asc
            """)
    List<TaskAssignment> findQueue(
            @Param("attenderId") Long attenderId, @Param("status") AssignmentStatus status);

    /** Every live complaint assignment, for the manager's list, in one query. */
    @Query(
            """
            select a from TaskAssignment a
              join fetch a.task t
              join fetch t.complaint c
             where a.status = :status
            """)
    List<TaskAssignment> findAllWithComplaint(@Param("status") AssignmentStatus status);
}
