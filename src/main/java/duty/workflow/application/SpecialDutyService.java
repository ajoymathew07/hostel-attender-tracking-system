package duty.workflow.application;

import duty.audit.application.AuditTrail.AuditDetail;
import duty.audit.application.AuditTrail.AuditEntry;
import duty.audit.application.AuditTrail;
import duty.identity.application.CurrentUser;
import duty.ledger.application.PointsLedger;
import duty.workflow.application.WorkflowService.GuardFailedException;
import duty.workflow.domain.Task.TaskStatus;
import duty.workflow.domain.Task.TaskType;
import duty.workflow.domain.Task;
import duty.workflow.domain.TaskAssignment.AssignMethod;
import duty.workflow.domain.TaskAssignment.AssignmentStatus;
import duty.workflow.domain.TaskAssignment;
import duty.workflow.infrastructure.TaskAssignmentRepository;
import duty.workflow.infrastructure.TaskRepository;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Special duties: work with no complaint and no docket that the manager posts and attenders claim
 * (section 5.2). {@code OPEN → CLAIMED → COMPLETED}, with {@code CANCELLED} and {@code EXPIRED} exits.
 *
 * <p>A shared duty has several slots. Each assignee is credited once for their part, under
 * {@code DUTY:{task}:{attender}}, and the duty completes when every assignee has finished. Claiming
 * and completing both lock the task row ({@code SELECT ... FOR UPDATE}), so two attenders racing for
 * the last slot get a clear answer, and the duty is completed exactly once.
 */
@Service
@Transactional
public class SpecialDutyService {

    private static final String ENTITY_TYPE = "task";

    /** The states in which a duty can still be claimed, cancelled or expired. */
    private static final Set<TaskStatus> LIVE = Set.of(TaskStatus.OPEN, TaskStatus.CLAIMED);

    private final TaskRepository tasks;
    private final TaskAssignmentRepository assignments;
    private final PointsLedger ledger;
    private final AuditTrail audit;

    SpecialDutyService(
            TaskRepository tasks,
            TaskAssignmentRepository assignments,
            PointsLedger ledger,
            AuditTrail audit) {
        this.tasks = tasks;
        this.assignments = assignments;
        this.ledger = ledger;
        this.audit = audit;
    }

    /**
     * Posts a duty.
     *
     * @param slots how many attenders may take part; more than 1 makes it shared
     * @param closesAt when it can no longer be claimed; {@code null} for no limit
     */
    @PreAuthorize("hasRole('MANAGER')")
    public long post(String title, String description, int points, int slots, Instant closesAt) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("a duty needs a title");
        }
        if (points < 1) {
            throw new IllegalArgumentException("a duty must be worth at least one point");
        }
        Instant now = Instant.now();
        if (closesAt != null && closesAt.isBefore(now)) {
            throw new IllegalArgumentException("the claim-by time has already passed");
        }
        Task duty = Task.specialDuty(title.trim(), points, CurrentUser.idOrNull(), now);
        duty.describeAs(description == null || description.isBlank() ? null : description.trim());
        if (slots > 1) {
            duty.shareAcross(slots);
        }
        duty.openBetween(now, closesAt);
        tasks.saveAndFlush(duty);
        record(duty, "DUTY_POSTED", null, AuditDetail.of("points", points).and("slots", slots).json());
        return duty.getId();
    }

    /** Takes one slot of a duty for the signed-in attender. */
    @PreAuthorize("hasRole('ATTENDER')")
    public void claim(long taskId) {
        long me = signedInId();
        Task duty = lockedDuty(taskId);
        Instant now = Instant.now();
        // CLAIMED means every place is taken; that is answered below as "no places left".
        if (!LIVE.contains(duty.getStatus()) || isPastClose(duty, now)) {
            throw new GuardFailedException("this duty is no longer open");
        }
        if (assignments.findByTaskIdAndAttenderId(taskId, me).isPresent()) {
            throw new GuardFailedException("you have already taken part in this duty");
        }
        long taken = slotsTaken(taskId);
        if (taken >= duty.getSlots()) {
            throw new NoSlotsLeftException();
        }
        assignments.saveAndFlush(new TaskAssignment(duty, me, AssignMethod.CLAIMED, me, now));
        if (taken + 1 == duty.getSlots()) {
            duty.moveTo(TaskStatus.CLAIMED);
        }
        record(duty, "DUTY_CLAIMED", null, null);
    }

    /**
     * Marks the signed-in attender's part done and credits them. The duty completes when nobody is still
     * working on it.
     *
     * @return the points credited by this call; {@code 0} on a repeated tap
     */
    @PreAuthorize("hasRole('ATTENDER')")
    public int complete(long taskId) {
        long me = signedInId();
        Task duty = lockedDuty(taskId);
        TaskAssignment mine =
                assignments
                        .findByTaskIdAndAttenderId(taskId, me)
                        .filter(assignment -> assignment.getStatus() != AssignmentStatus.RELEASED)
                        .orElseThrow(() -> new AccessDeniedException("this duty is not yours"));
        Instant now = Instant.now();
        boolean credited = ledger.creditForDuty(me, taskId, duty.getPointValue(), now);
        if (mine.isActive()) {
            mine.complete();
            assignments.saveAndFlush(mine);
        }
        if (assignments.countByTaskIdAndStatus(taskId, AssignmentStatus.ACTIVE) == 0
                && duty.getStatus() != TaskStatus.COMPLETED) {
            String from = duty.getStatus().name();
            duty.moveTo(TaskStatus.COMPLETED);
            record(duty, "DUTY_COMPLETED", from, null);
        }
        return credited ? duty.getPointValue() : 0;
    }

    /** Withdraws a duty. Anyone already credited keeps their points. */
    @PreAuthorize("hasRole('MANAGER')")
    public void cancel(long taskId) {
        Task duty = lockedDuty(taskId);
        if (!LIVE.contains(duty.getStatus())) {
            throw new GuardFailedException("only an open or claimed duty can be cancelled");
        }
        end(duty, TaskStatus.CANCELLED);
    }

    /**
     * Expires every live duty whose closing time has passed (scheduled). Anyone already credited keeps
     * their points.
     *
     * @return how many duties expired
     */
    public int expireOverdue() {
        Instant now = Instant.now();
        int expired = 0;
        for (Task duty : liveDutyTasks()) {
            if (isPastClose(duty, now)) {
                end(tasks.findByIdForUpdate(duty.getId()).orElseThrow(), TaskStatus.EXPIRED);
                expired++;
            }
        }
        return expired;
    }

    /** Duties the signed-in attender can still claim. */
    @Transactional(readOnly = true)
    public List<DutyView> claimable() {
        Instant now = Instant.now();
        long me = signedInId();
        return liveDutyTasks().stream()
                .filter(duty -> duty.getStatus() == TaskStatus.OPEN && !isPastClose(duty, now))
                .filter(duty -> assignments.findByTaskIdAndAttenderId(duty.getId(), me).isEmpty())
                .map(this::toView)
                .toList();
    }

    /** Duties the signed-in attender has claimed and not yet finished. */
    @Transactional(readOnly = true)
    public List<DutyView> mine() {
        return assignments.findByAttenderIdAndStatus(signedInId(), AssignmentStatus.ACTIVE).stream()
                .map(TaskAssignment::getTask)
                .filter(task -> task.getTaskType() == TaskType.SPECIAL_DUTY)
                .map(this::toView)
                .toList();
    }

    /** Every open or claimed duty, for the manager. */
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('MANAGER')")
    public List<DutyView> live() {
        return liveDutyTasks().stream().map(this::toView).toList();
    }

    private List<Task> liveDutyTasks() {
        return tasks.findByTaskTypeAndStatusInOrderByCreatedAtDesc(TaskType.SPECIAL_DUTY, LIVE);
    }

    /** Releases whoever is still working on it and moves it to {@code to}. */
    private void end(Task duty, TaskStatus to) {
        String from = duty.getStatus().name();
        assignments
                .findByTaskIdAndStatus(duty.getId(), AssignmentStatus.ACTIVE)
                .forEach(TaskAssignment::release);
        duty.moveTo(to);
        record(duty, "DUTY_" + to.name(), from, null);
    }

    /** Slots in use: people working on it now, plus people who have finished their part. */
    private long slotsTaken(long taskId) {
        return assignments.countByTaskIdAndStatus(taskId, AssignmentStatus.ACTIVE)
                + assignments.countByTaskIdAndStatus(taskId, AssignmentStatus.COMPLETED);
    }

    private Task lockedDuty(long taskId) {
        return tasks.findByIdForUpdate(taskId)
                .filter(task -> task.getTaskType() == TaskType.SPECIAL_DUTY)
                .orElseThrow(() -> new GuardFailedException("no such duty"));
    }

    private static boolean isPastClose(Task duty, Instant now) {
        return duty.getClosesAt() != null && now.isAfter(duty.getClosesAt());
    }

    private static long signedInId() {
        Long me = CurrentUser.idOrNull();
        if (me == null) {
            throw new AccessDeniedException("nobody is signed in");
        }
        return me;
    }

    private void record(Task duty, String eventType, String from, String detail) {
        audit.record(
                new AuditEntry(
                        null,
                        CurrentUser.idOrNull(),
                        CurrentUser.roleOrNull(),
                        ENTITY_TYPE,
                        duty.getId(),
                        eventType,
                        from,
                        duty.getStatus().name(),
                        detail,
                        null));
    }

    private DutyView toView(Task duty) {
        List<Long> holders =
                assignments.findByTaskIdAndStatus(duty.getId(), AssignmentStatus.ACTIVE).stream()
                        .map(TaskAssignment::getAttenderId)
                        .toList();
        return new DutyView(
                duty.getId(),
                duty.getTitle(),
                duty.getDescription(),
                duty.getPointValue(),
                duty.getSlots(),
                slotsTaken(duty.getId()),
                duty.getClosesAt(),
                duty.getStatus(),
                holders);
    }

    /**
     * One duty, as the screens show it.
     *
     * @param taken slots in use or already finished
     * @param holderIds attenders still working on it
     */
    public record DutyView(
            long taskId,
            String title,
            String description,
            int points,
            int slots,
            long taken,
            Instant closesAt,
            TaskStatus status,
            List<Long> holderIds) {}

    /** Every slot of the duty is taken (test X5). */
    public static class NoSlotsLeftException extends GuardFailedException {

        private static final long serialVersionUID = 1L;

        public NoSlotsLeftException() {
            super("every place on this duty is already taken");
        }
    }
}
