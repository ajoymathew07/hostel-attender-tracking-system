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
 * One attender's hold on one task.
 *
 * <p>Unique per task and attender. {@code @Version} stops a reassignment and a resolve overwriting each
 * other. {@code scoreSnapshot} records why dispatch chose them.
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
     * Puts the attender back on the task after a release. The row is reused because only one row per task
     * and attender is allowed.
     */
    public void assignAgain(Long assigner, Instant at) {
        this.assignMethod = AssignMethod.MANUAL;
        this.assignedBy = assigner;
        this.assignedAt = Objects.requireNonNull(at, "assignedAt");
        this.status = AssignmentStatus.ACTIVE;
    }

    /** Released, or replaced by a reassignment. */
    public void release() {
        this.status = AssignmentStatus.RELEASED;
    }

    /** The attender finished their part. */
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

    /** How an assignment was made. Matches {@code ck_asg_method}. */
    public enum AssignMethod {

        /** Chosen by the dispatch heuristic; {@code score_snapshot} records why. */
        AUTO,

        /** A manager assigned or reassigned it. */
        MANUAL,

        /** An attender claimed a posted special duty. */
        CLAIMED
    }

    /** State of one attender's hold on a task. */
    public enum AssignmentStatus {

        /** The attender currently owns the work. */
        ACTIVE,

        /** Handed back, or superseded by a reassignment. */
        RELEASED,

        /** The attender finished their part. */
        COMPLETED
    }
}
