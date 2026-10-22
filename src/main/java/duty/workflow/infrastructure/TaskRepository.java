package duty.workflow.infrastructure;

import duty.workflow.domain.Task.TaskStatus;
import duty.workflow.domain.Task.TaskType;
import duty.workflow.domain.Task;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Tasks. */
public interface TaskRepository extends JpaRepository<Task, Long> {

    List<Task> findByStatusAndTaskType(TaskStatus status, TaskType taskType);

    List<Task> findByTaskTypeAndStatusInOrderByCreatedAtDesc(TaskType taskType, Collection<TaskStatus> statuses);

    Optional<Task> findByComplaintId(Long complaintId);

    /**
     * Loads a task with {@code SELECT ... FOR UPDATE}, for claiming a shared duty: the loser gets a clear
     * answer at once instead of a confusing retry.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Task t where t.id = :id")
    Optional<Task> findByIdForUpdate(@Param("id") Long id);
}
