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
 * A unit of creditable work: either complaint-derived or a posted special duty (data model section
 * 3.6).
 *
 * <p>{@code ck_task_link} keeps the two shapes honest at the database level — a {@code COMPLAINT}
 * task must reference a complaint, a {@code SPECIAL_DUTY} must not.
 *
 * <p>{@code @Version} is what resolves C3: two assignees of a shared duty finishing at once both
 * read the same version, the second to commit fails and retries, sees the task already completed,
 * and skips the transition. Its own credit is keyed uniquely and is unaffected (section 7.5).
 */
@Entity
@Table(name = "task")
public class Task {

    /** The default credit for resolving a complaint. Configurable (data model section 3.6). */
    public static final int DEFAULT_COMPLAINT_POINTS = 2;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "task_type", nullable = false, length = 16)
    private TaskType taskType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "complaint_id")
    private Complaint complaint;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "point_value", nullable = false)
    private int pointValue;

    /** The common-room overhaul case: several attenders hold ACTIVE assignments at once. */
    @Column(name = "shared", nullable = false)
    private boolean shared;

    /**
     * How many attenders may hold an {@code ACTIVE} assignment at once — the {@code k} of test X5
     * and of the claim path in section 7.6. Always 1 unless the task is shared, which
     * {@code ck_task_slots} enforces in the database too.
     */
    @Column(name = "slots", nullable = false)
    private int slots = 1;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private TaskStatus status;

    @Column(name = "opens_at")
    private Instant opensAt;

    @Column(name = "closes_at")
    private Instant closesAt;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /** For JPA only. */
    protected Task() {}

    private Task(
            TaskType taskType,
            Complaint complaint,
            String title,
            int pointValue,
            Long createdBy,
            Instant createdAt) {
        this.taskType = taskType;
        this.complaint = complaint;
        this.title = Objects.requireNonNull(title, "title");
        this.pointValue = pointValue;
        this.status = TaskStatus.OPEN;
        // Null is the dispatcher: T2 creates a task during ingestion with nobody signed in.
        this.createdBy = createdBy;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.shared = false;
        this.slots = 1;
    }

    /** Honours {@code ck_task_link}: a complaint task always carries its complaint. */
    public static Task forComplaint(
            Complaint complaint, String title, int pointValue, Long createdBy, Instant createdAt) {
        Objects.requireNonNull(complaint, "complaint");
        return new Task(TaskType.COMPLAINT, complaint, title, pointValue, createdBy, createdAt);
    }

    /** Honours {@code ck_task_link}: a special duty never carries one. */
    public static Task specialDuty(
            String title, int pointValue, Long createdBy, Instant createdAt) {
        return new Task(TaskType.SPECIAL_DUTY, null, title, pointValue, createdBy, createdAt);
    }

    public void describeAs(String newDescription) {
        this.description = newDescription;
    }

    public void openBetween(Instant from, Instant to) {
        if (from != null && to != null && to.isBefore(from)) {
            throw new IllegalArgumentException("closesAt must not precede opensAt");
        }
        this.opensAt = from;
        this.closesAt = to;
    }

    /** Shares this task with {@code slots} concurrent assignees. Honours {@code ck_task_slots}. */
    public void shareAcross(int concurrentSlots) {
        if (concurrentSlots < 1) {
            throw new IllegalArgumentException("slots must be at least 1");
        }
        this.shared = true;
        this.slots = concurrentSlots;
    }

    public void markShared() {
        shareAcross(this.slots > 1 ? this.slots : 1);
    }

    public void moveTo(TaskStatus newStatus) {
        this.status = Objects.requireNonNull(newStatus, "status");
    }

    public int getSlots() {
        return slots;
    }

    public Long getId() {
        return id;
    }

    public TaskType getTaskType() {
        return taskType;
    }

    public Complaint getComplaint() {
        return complaint;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public int getPointValue() {
        return pointValue;
    }

    public boolean isShared() {
        return shared;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public Instant getOpensAt() {
        return opensAt;
    }

    public Instant getClosesAt() {
        return closesAt;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public long getVersion() {
        return version;
    }
}
