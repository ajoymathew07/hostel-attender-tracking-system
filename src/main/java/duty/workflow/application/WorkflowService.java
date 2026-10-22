package duty.workflow.application;

import duty.audit.application.AuditTrail.AuditDetail;
import duty.audit.application.AuditTrail.AuditEntry;
import duty.audit.application.AuditTrail;
import duty.identity.application.CurrentUser;
import duty.ledger.application.PointsLedger;
import duty.workflow.domain.Complaint.ComplaintDomain;
import duty.workflow.domain.Complaint.ComplaintStatus;
import duty.workflow.domain.Complaint;
import duty.workflow.domain.ComplaintTransitions.ComplaintTransition;
import duty.workflow.domain.ComplaintTransitions.TransitionActor;
import duty.workflow.domain.ComplaintTransitions.TransitionGuard;
import duty.workflow.domain.ComplaintTransitions.WorkflowEvent;
import duty.workflow.domain.ComplaintTransitions;
import duty.workflow.domain.Task.TaskStatus;
import duty.workflow.domain.Task;
import duty.workflow.domain.TaskAssignment.AssignmentStatus;
import duty.workflow.domain.TaskAssignment;
import duty.workflow.infrastructure.ComplaintRepository;
import duty.workflow.infrastructure.TaskAssignmentRepository;
import duty.workflow.infrastructure.TaskRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only code that changes {@code complaint.status}.
 *
 * <p>{@link #transition}:
 * <ol>
 *   <li>loads the complaint with its {@code @Version} (optimistic lock);
 *   <li>looks up the move in {@link ComplaintTransitions}, refusing it if absent;
 *   <li>checks who is acting and the move's condition;
 *   <li>applies the change, its side effects and the audit row in one transaction.
 * </ol>
 *
 * <p>The actor comes from the security context, not a parameter, so a caller cannot fake it. The
 * one exception is act-as: the manager may name an attender in {@link TransitionPayload#onBehalfOf},
 * and the move then runs exactly as if that attender had made it, with the manager recorded as the
 * actor. The side effects of T2 and T3 are written by {@link AutoDispatchService}.
 */
@Service
@Transactional
public class WorkflowService {

    private static final String ENTITY_TYPE = "complaint";

    /** Auto-close happens 7 days after resolution (T16). */
    private static final Duration AUTO_CLOSE_AFTER = Duration.ofDays(7);

    private final ComplaintRepository complaints;
    private final TaskRepository tasks;
    private final TaskAssignmentRepository assignments;
    private final PointsLedger ledger;
    private final AuditTrail audit;

    /** Docket number format; only the format can be checked. */
    private final Pattern docketPattern;

    WorkflowService(
            ComplaintRepository complaints,
            TaskRepository tasks,
            TaskAssignmentRepository assignments,
            PointsLedger ledger,
            AuditTrail audit,
            @Value("${workflow.docket-pattern}") String docketPattern) {
        this.complaints = complaints;
        this.tasks = tasks;
        this.assignments = assignments;
        this.ledger = ledger;
        this.audit = audit;
        this.docketPattern = Pattern.compile(docketPattern);
    }

    /**
     * Moves a complaint, or refuses to.
     *
     * @throws IllegalTransitionException the move is not allowed from the current state
     * @throws GuardFailedException the move is allowed but its condition is not met
     * @throws AccessDeniedException the caller is not allowed to make this move
     */
    public void transition(long complaintId, WorkflowEvent event, TransitionPayload payload) {
        Complaint complaint = complaints.findById(complaintId).orElseThrow();
        ComplaintStatus from = complaint.getStatus();

        List<ComplaintTransition> rows = ComplaintTransitions.matching(from, event);
        if (rows.isEmpty()) {
            throw new IllegalTransitionException(from, event);
        }

        Task task = tasks.findByComplaintId(complaintId).orElse(null);
        Long onBehalfOf = payload.onBehalfOf();
        if (onBehalfOf != null && !isManager()) {
            throw new AccessDeniedException("only the manager can act for an attender");
        }
        TaskAssignment acting = assignmentOf(task, onBehalfOf != null ? onBehalfOf : CurrentUser.idOrNull());
        Context context = new Context(complaint, task, acting, payload);

        // Every row for one pair names the same actor — the T2/T3 fork is in the guard, not in who
        // may fire it — so authorization is settled before the branch is chosen.
        requireActor(rows.get(0).actor(), context);

        ComplaintTransition row =
                rows.stream()
                        .filter(candidate -> satisfied(candidate.guard(), context))
                        .findFirst()
                        .orElseThrow(() -> refusal(rows.get(0).guard(), context));

        Instant now = Instant.now();
        String detail = applySideEffects(event, context, now);
        complaint.moveTo(row.to(), now);
        audit.record(
                new AuditEntry(
                        now,
                        CurrentUser.idOrNull(),
                        CurrentUser.roleOrNull(),
                        ENTITY_TYPE,
                        complaint.getId(),
                        event.name(),
                        from.name(),
                        row.to().name(),
                        detail,
                        null,
                        onBehalfOf));
        // Flushed here so the version check fires inside this call rather than at some later
        // commit: the loser of a race must be told, not silently allowed to clobber (section 7.2).
        complaints.saveAndFlush(complaint);
    }

    /**
     * Closes every complaint resolved more than seven days ago that nobody signed off (T16, scheduled).
     *
     * @return how many were closed
     */
    public int autoCloseResolved() {
        List<Complaint> due =
                complaints.findByStatusAndResolvedAtBefore(
                        ComplaintStatus.RESOLVED, Instant.now().minus(AUTO_CLOSE_AFTER));
        for (Complaint complaint : due) {
            transition(complaint.getId(), WorkflowEvent.AUTO_CLOSE, TransitionPayload.none());
        }
        return due.size();
    }

    /** What this user holds on the complaint's task, if anything. */
    private TaskAssignment assignmentOf(Task task, Long userId) {
        if (task == null || userId == null) {
            return null;
        }
        return assignments.findByTaskIdAndAttenderId(task.getId(), userId).orElse(null);
    }

    /** Checks the caller is the actor the transition requires. */
    private void requireActor(TransitionActor actor, Context context) {
        boolean permitted =
                switch (actor) {
                    // A scheduled job or the dispatcher; no route exposes these events.
                    case SYSTEM -> true;
                    case MANAGER -> isManager();
                    case ASSIGNED_ATTENDER -> holdsActiveAssignment(context);
                    case ATTENDER_OR_MANAGER -> isManager() || holdsActiveAssignment(context);
                };
        if (!permitted) {
            throw new AccessDeniedException("this complaint is not yours to move");
        }
    }

    private static boolean isManager() {
        return "MANAGER".equals(CurrentUser.roleOrNull());
    }

    /**
     * Whether the caller holds an {@code ACTIVE} assignment. A released or completed one does not count.
     */
    private static boolean holdsActiveAssignment(Context context) {
        return context.assignment() != null && context.assignment().isActive();
    }

    /** Whether the transition's condition holds. */
    private boolean satisfied(TransitionGuard guard, Context context) {
        return switch (guard) {
            case NONE -> true;
            case DISPATCH_CANDIDATE -> dispatchFoundSomebody(context);
            case NO_DISPATCH_CANDIDATE -> !dispatchFoundSomebody(context);
            case TARGET_ATTENDER -> context.payload().attenderId() != null;
            case DIFFERENT_ATTENDER -> movesTheWork(context);
            case REASON -> hasText(context.payload().reason());
            case DOCKET_FORMAT ->
                    hasText(context.payload().docketNo())
                            && docketPattern.matcher(context.payload().docketNo().trim()).matches();
            case DOCKET_FILED -> context.complaint().hasDocket();
            case SEVEN_DAYS_SINCE_RESOLUTION ->
                    context.complaint().getResolvedAt() != null
                            && !Instant.now()
                                    .isBefore(context.complaint().getResolvedAt().plus(AUTO_CLOSE_AFTER));
        };
    }

    /** The exception for a failed condition; the docket ones have their own messages. */
    private RuntimeException refusal(TransitionGuard guard, Context context) {
        return switch (guard) {
            case DOCKET_FORMAT -> new InvalidDocketException(context.payload().docketNo());
            case DOCKET_FILED -> new DocketRequiredException(context.task().getId());
            default -> new GuardFailedException(guard.reason());
        };
    }

    /** T2's condition: a dispatchable domain and an attender was found. */
    private static boolean dispatchFoundSomebody(Context context) {
        return context.payload().attenderId() != null
                && context.complaint().getDomain() != ComplaintDomain.UNCLASSIFIED;
    }

    /** T6's condition: the work actually moves to a different attender. */
    private boolean movesTheWork(Context context) {
        Long target = context.payload().attenderId();
        return target != null
                && assignments
                        .findByTaskIdAndStatus(context.task().getId(), AssignmentStatus.ACTIVE)
                        .stream()
                        .noneMatch(held -> held.getAttenderId().equals(target));
    }

    /**
     * Applies the transition's side effects and returns the audit detail.
     *
     * <p>Assignment rows are managed by {@link ManualAssignmentService} and {@link AutoDispatchService},
     * not here.
     */
    private String applySideEffects(WorkflowEvent event, Context context, Instant now) {
        switch (event) {
            case ATTENDER_START -> context.complaint().startInspection(now);
            // T12/T13: the SLA clock pauses while blocked, so waiting on the Engineering Unit
            // is not counted against the attender.
            case BLOCK -> context.complaint().pauseSlaClock(now);
            case UNBLOCK -> {
                return resumeTheClock(context, now);
            }
            case ATTENDER_RELEASE -> releaseHolders(context.task());
            case DOCKET_RECORDED -> {
                return recordDocket(context, now);
            }
            case MARK_RESOLVED -> creditAndComplete(context, now);
            case MANAGER_REOPEN -> reverseTheCredit(context, now);
            default -> {
                // T5, T9, T10, T15, T16 and the two dispatch rows change state and nothing else.
            }
        }
        return hasText(context.payload().reason())
                ? AuditDetail.of("reason", context.payload().reason()).json()
                : null;
    }

    /** Restarts the SLA clock (T13) and records in the audit row how long the hold lasted. */
    private String resumeTheClock(Context context, Instant now) {
        Complaint complaint = context.complaint();
        Instant heldSince = complaint.getHoldStartedAt();
        complaint.resumeSlaClock(now);
        return AuditDetail.of("heldSince", Objects.toString(heldSince, null))
                .and("slaDueAt", Objects.toString(complaint.getSlaDueAt(), null))
                .json();
    }

    /** Records the docket (T11); the audit row keeps the old and new numbers. */
    private String recordDocket(Context context, Instant now) {
        String previous = context.complaint().getDocketNo();
        String corrected = context.payload().docketNo().trim();
        context.complaint().recordDocket(corrected, CurrentUser.idOrNull(), now);
        return AuditDetail.of("from", previous).and("to", corrected).json();
    }

    /** Releases the current holders (T8). */
    private void releaseHolders(Task task) {
        assignments
                .findByTaskIdAndStatus(task.getId(), AssignmentStatus.ACTIVE)
                .forEach(TaskAssignment::release);
    }

    /** Credits the points, keyed to the work so a retry cannot double them (T14). */
    private void creditAndComplete(Context context, Instant now) {
        TaskAssignment mine = context.assignment();
        Task task = context.task();
        ledger.creditForResolve(
                mine.getAttenderId(),
                task.getId(),
                task.getPointValue(),
                context.complaint().getReopenCount(),
                now);
        mine.complete();
        task.moveTo(TaskStatus.COMPLETED);
    }

    /**
     * Reverses the credit on reopen with a negating row (T17). The reopen count goes up last, so a later
     * re-resolution gets a new key and is credited again.
     */
    private void reverseTheCredit(Context context, Instant now) {
        Task task = context.task();
        int reopenCount = context.complaint().getReopenCount();
        for (TaskAssignment done :
                assignments.findByTaskIdAndStatus(task.getId(), AssignmentStatus.COMPLETED)) {
            ledger.reverseResolveCredit(done.getAttenderId(), task.getId(), reopenCount, now);
            // The work is theirs again, or nobody could redo it.
            done.assignAgain(CurrentUser.idOrNull(), now);
        }
        task.moveTo(TaskStatus.OPEN);
        context.complaint().reopen(now);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** Everything a condition or side effect may read, loaded once per transition. */
    private record Context(
            Complaint complaint, Task task, TaskAssignment assignment, TransitionPayload payload) {}

    /**
     * Refuses to resolve a complaint that has no Engineering Unit docket number. No docket, no points.
     */
    public static class DocketRequiredException extends GuardFailedException {

        private static final long serialVersionUID = 1L;

        public DocketRequiredException(long taskId) {
            super("Task " + taskId + " has no CMS docket; it cannot be resolved for points");
        }
    }

    /**
     * The move is allowed from this state, but its condition is not met (e.g. a missing reason).
     *
     * <p>Separate from {@link IllegalTransitionException} so the message can tell the user which one
     * happened.
     */
    public static class GuardFailedException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public GuardFailedException(String reason) {
            super(reason);
        }
    }

    /**
     * The move is not allowed from the complaint's current state. Thrown before anything is written.
     *
     * <p>Usually caused by a stale screen, e.g. the same task open on two phones.
     */
    public static class IllegalTransitionException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public IllegalTransitionException(ComplaintStatus from, WorkflowEvent event) {
            super("A complaint that is " + from + " cannot be moved by " + event);
        }
    }

    /**
     * The docket number does not match the configured format. There is no Engineering Unit API, so only
     * the format can be checked.
     */
    public static class InvalidDocketException extends GuardFailedException {

        private static final long serialVersionUID = 1L;

        private final String docketNo;

        public InvalidDocketException(String docketNo) {
            super("Not a usable CMS docket number");
            this.docketNo = docketNo;
        }

        public String getDocketNo() {
            return docketNo;
        }
    }

    /**
     * Extra values a transition may need: a reason, a docket number, or an attender. One record with
     * nullable fields rather than a class per event.
     *
     * @param reason required for reject, release, no-defect, block and reopen
     * @param docketNo the Engineering Unit's number (T11)
     * @param attenderId who the work goes to (T2, T4, T6)
     * @param onBehalfOf the attender the manager is acting for (act-as); {@code null} otherwise
     */
    public record TransitionPayload(String reason, String docketNo, Long attenderId, Long onBehalfOf) {

        /** No extra values. */
        public static TransitionPayload none() {
            return new TransitionPayload(null, null, null, null);
        }

        public static TransitionPayload reason(String reason) {
            return new TransitionPayload(reason, null, null, null);
        }

        public static TransitionPayload docket(String docketNo) {
            return new TransitionPayload(null, docketNo, null, null);
        }

        public static TransitionPayload attender(long attenderId) {
            return new TransitionPayload(null, null, attenderId, null);
        }

        /** The same values, applied by the manager for this attender. */
        public TransitionPayload actingFor(long attender) {
            return new TransitionPayload(reason, docketNo, attenderId, attender);
        }
    }
}
