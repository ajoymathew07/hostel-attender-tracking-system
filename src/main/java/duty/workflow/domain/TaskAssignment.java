package duty.workflow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;

/**
 * One attender's hold on one task (data model section 3.6).
 *
 * <p>{@code uq_task_attender} makes a double claim by the same attender impossible regardless of
 * what the claim path does (section 7.6), and {@code @Version} guards the reassign-versus-resolve
 * race C2 (section 7.7).
 *
 * <p>{@code scoreSnapshot} records the computed dispatch score that chose this attender. It is the
 * cheap substitute for time-bounded coverage: "why them" is answered from the snapshot rather than
 * by replaying a historical roster (section 3.4).
 */
@Entity
@Table(name = "task_assignment")
public class TaskAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false)
    private Task task;

    @Column(name = "attender_id", nullable = false)
    private Long attenderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "assign_method", nullable = false, length = 16)
    private AssignMethod assignMethod;

    @Column(name = "assigned_by")
    private Long assignedBy;

    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private AssignmentStatus status;

    /** JSON of the dispatch score that chose them. */
    @Column(name = "score_snapshot", columnDefinition = "text")
    private String scoreSnapshot;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /** For JPA only. */
    protected TaskAssignment() {}

    public TaskAssignment(
            Task task,
            Long attenderId,
            AssignMethod assignMethod,
            Long assignedBy,
            Instant assignedAt) {
        this.task = Objects.requireNonNull(task, "task");
        this.attenderId = Objects.requireNonNull(attenderId, "attenderId");
        this.assignMethod = Objects.requireNonNull(assignMethod, "assignMethod");
        this.assignedBy = assignedBy;
        this.assignedAt = Objects.requireNonNull(assignedAt, "assignedAt");
        this.status = AssignmentStatus.ACTIVE;
    }

    public void recordScoreSnapshot(String json) {
        this.scoreSnapshot = json;
    }

    /**
     * Puts this attender back on the task after a release (transition T6).
     *
     * <p>{@code uq_task_attender} allows one row per (task, attender) ever, so a manager who
     * releases a task and then hands it back to the same attender has to revive this row rather
     * than insert a second one.
     */
    public void assignAgain(Long assigner, Instant at) {
        this.assignMethod = AssignMethod.MANUAL;
        this.assignedBy = assigner;
        this.assignedAt = Objects.requireNonNull(at, "assignedAt");
        this.status = AssignmentStatus.ACTIVE;
    }

    /** Handed back or superseded by a reassignment (transitions T6, T8). */
    public void release() {
        this.status = AssignmentStatus.RELEASED;
    }

    /** The attender marked their part done (transition T14, section 7.5). */
    public void complete() {
        this.status = AssignmentStatus.COMPLETED;
    }

    public boolean isActive() {
        return status == AssignmentStatus.ACTIVE;
    }

    public Long getId() {
        return id;
    }

    public Task getTask() {
        return task;
    }

    public Long getAttenderId() {
        return attenderId;
    }

    public AssignMethod getAssignMethod() {
        return assignMethod;
    }

    public Long getAssignedBy() {
        return assignedBy;
    }

    public Instant getAssignedAt() {
        return assignedAt;
    }

    public AssignmentStatus getStatus() {
        return status;
    }

    public String getScoreSnapshot() {
        return scoreSnapshot;
    }

    public long getVersion() {
        return version;
    }
}
