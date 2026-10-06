package duty.workflow.infrastructure;

import duty.workflow.domain.Task;
import duty.workflow.domain.TaskStatus;
import duty.workflow.domain.TaskType;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Tasks (data model section 3.6). */
public interface TaskRepository extends JpaRepository<Task, Long> {

    List<Task> findByStatusAndTaskType(TaskStatus status, TaskType taskType);

    Optional<Task> findByComplaintId(Long complaintId);

    /**
     * {@code SELECT ... FOR UPDATE} for the claim path (section 7.6, ADR-006).
     *
     * <p>Claiming is a genuine read-modify-write on a count, and optimistic locking would handle it
     * — but the retry is user-visible and confusing for someone standing in a corridor. Contention
     * is trivially low, so a short pessimistic lock buys an immediate, clear answer for the loser.
     * The lock is held for microseconds.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Task t where t.id = :id")
    Optional<Task> findByIdForUpdate(@Param("id") Long id);
}
