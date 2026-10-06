package duty.workflow.infrastructure;

import duty.workflow.domain.AssignmentStatus;
import duty.workflow.domain.TaskAssignment;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /** The anti-starvation tie-break of section 6.2: how much this attender was given today. */
    long countByAttenderIdAndAssignedAtGreaterThanEqual(Long attenderId, Instant from);

    /** The second tie-break: longest since the last assignment, whatever became of it. */
    Optional<TaskAssignment> findFirstByAttenderIdOrderByAssignedAtDesc(Long attenderId);

    /**
     * One attender's queue, complaint and all (section 10.2).
     *
     * <p>The fetch joins are the whole point: the card shows the complaint's text, room, domain
     * and priority, so loading them lazily would be one query per card on the slowest connection
     * in the system (section 10.6, NFR-3).
     *
     * <p>{@code join fetch t.complaint} is an inner join, so special duties — which have no
     * complaint by {@code ck_task_link} — are excluded. That is the right queue for this screen;
     * duties are a separate tab at {@code /m/duties}.
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

    /** Every live complaint assignment, for the manager's list — one query, not one per row. */
    @Query(
            """
            select a from TaskAssignment a
              join fetch a.task t
              join fetch t.complaint c
             where a.status = :status
            """)
    List<TaskAssignment> findAllWithComplaint(@Param("status") AssignmentStatus status);
}
