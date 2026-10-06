package duty.workflow.application;

import duty.audit.application.AuditDetail;
import duty.audit.application.AuditEntry;
import duty.audit.application.AuditTrail;
import duty.identity.application.CurrentUser;
import duty.ledger.application.PointsLedger;
import duty.workflow.domain.AssignmentStatus;
import duty.workflow.domain.Complaint;
import duty.workflow.domain.ComplaintDomain;
import duty.workflow.domain.ComplaintStatus;
import duty.workflow.domain.ComplaintTransition;
import duty.workflow.domain.ComplaintTransitions;
import duty.workflow.domain.Task;
import duty.workflow.domain.TaskAssignment;
import duty.workflow.domain.TaskStatus;
import duty.workflow.domain.TransitionActor;
import duty.workflow.domain.TransitionGuard;
import duty.workflow.domain.WorkflowEvent;
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
 * <strong>The one and only write path to {@code complaint.status}</strong> (section 4.1).
 *
 * <p>Complaint status drives the attender queue, the dashboard, SLA aging, MTTR and the point
 * credit. Left as an informally-updated string it is how a ticket ends up {@code RESOLVED} with no
 * docket, or credited twice. So every change goes through {@link #transition}, which
 *
 * <ol>
 *   <li>loads the aggregate with its {@code @Version} — the optimistic lock of section 7.2;
 *   <li>looks the pair up in {@link ComplaintTransitions}, refusing an absent one;
 *   <li>checks the actor and evaluates the guard;
 *   <li>applies the state change, its side effects and its audit row in <em>one</em> transaction.
 * </ol>
 *
 * <p><strong>The actor is read from the security context, not passed in.</strong> Section 4.1
 * writes the signature as {@code transition(complaintId, event, actor, payload)}, but every caller
 * in this codebase would fill that argument with the signed-in user, and an actor a caller can
 * choose is an actor a caller can forge. {@code CurrentUser} is where the rest of the system reads
 * it, for the same reason.
 *
 * <p>T2 and T3 are rows here, but their side effects — snapshot the dispatch score, record the
 * triage reason — are written by {@link AutoDispatchService}, which then calls this for the state
 * move.
 */
@Service
@Transactional
public class WorkflowService {

    private static final String ENTITY_TYPE = "complaint";

    /** T16 — "7 days elapsed since resolution". */
    private static final Duration AUTO_CLOSE_AFTER = Duration.ofDays(7);

    private final ComplaintRepository complaints;
    private final TaskRepository tasks;
    private final TaskAssignmentRepository assignments;
    private final PointsLedger ledger;
    private final AuditTrail audit;

    /** OQ-1: there is no CMS API (section 1.8), so T11 can only check the format. */
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
     * @throws IllegalTransitionException the pair is not in section 4.2's table
     * @throws GuardFailedException the move is legal but its precondition is not met
     * @throws AccessDeniedException the caller is not the actor the row names (section 11.3)
     */
    public void transition(long complaintId, WorkflowEvent event, TransitionPayload payload) {
        Complaint complaint = complaints.findById(complaintId).orElseThrow();
        ComplaintStatus from = complaint.getStatus();

        List<ComplaintTransition> rows = ComplaintTransitions.matching(from, event);
        if (rows.isEmpty()) {
            throw new IllegalTransitionException(from, event);
        }

        Task task = tasks.findByComplaintId(complaintId).orElse(null);
        Context context = new Context(complaint, task, callersAssignment(task), payload);

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
                        null));
        // Flushed here so the version check fires inside this call rather than at some later
        // commit: the loser of a race must be told, not silently allowed to clobber (section 7.2).
        complaints.saveAndFlush(complaint);
    }

    /** What the caller holds on this complaint's task, if anything. */
    private TaskAssignment callersAssignment(Task task) {
        Long me = CurrentUser.idOrNull();
        if (task == null || me == null) {
            return null;
        }
        return assignments.findByTaskIdAndAttenderId(task.getId(), me).orElse(null);
    }

    /** The Actor column of section 4.2, which is section 11.3 enforced at the service layer. */
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
     * Ownership, not role. A {@code RELEASED} or {@code COMPLETED} row records that the caller
     * <em>once</em> held the task, which is exactly the person who must no longer drive it (E20).
     */
    private static boolean holdsActiveAssignment(Context context) {
        return context.assignment() != null && context.assignment().isActive();
    }

    /** The Guard column of section 4.2, evaluated. */
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

    /** The refusal the failed guard deserves; the docket's two have sentences of their own. */
    private RuntimeException refusal(TransitionGuard guard, Context context) {
        return switch (guard) {
            case DOCKET_FORMAT -> new InvalidDocketException(context.payload().docketNo());
            case DOCKET_FILED -> new DocketRequiredException(context.task().getId());
            default -> new GuardFailedException(guard.reason());
        };
    }

    /** T2 — "Domain is not UNCLASSIFIED and dispatch returns a candidate". */
    private static boolean dispatchFoundSomebody(Context context) {
        return context.payload().attenderId() != null
                && context.complaint().getDomain() != ComplaintDomain.UNCLASSIFIED;
    }

    /** T6 — a reassignment that leaves the work where it is, is not a reassignment. */
    private boolean movesTheWork(Context context) {
        Long target = context.payload().attenderId();
        return target != null
                && assignments
                        .findByTaskIdAndStatus(context.task().getId(), AssignmentStatus.ACTIVE)
                        .stream()
                        .noneMatch(held -> held.getAttenderId().equals(target));
    }

    /**
     * The Side effects column of section 4.2, and the audit detail they produce.
     *
     * <p>T2's score snapshot and T3's triage reason are not here: {@link AutoDispatchService}
     * writes them. The triage reason still reaches the audit row, as the payload's {@code reason}.
     *
     * <p>Assignment rows are not created here either. {@link ManualAssignmentService} owns them and
     * calls this method for the status move, which is what section 4.1 says must be single —
     * status, not every row a manager touches.
     */
    private String applySideEffects(WorkflowEvent event, Context context, Instant now) {
        switch (event) {
            case ATTENDER_START -> context.complaint().startInspection(now);
            // T12 and T13 — the SLA clock stops while the work is blocked and the due date moves by
            // however long the block lasted (section 4.5). Without this, "waiting three days for the
            // Engineering Unit" would be recorded as attender slowness and would point section 8's
            // bottleneck analysis at exactly the wrong stage.
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

    /**
     * T13 — the clock restarts, and the audit row says what the hold cost.
     *
     * <p>The hold's length is on the audit row rather than in a column of its own: the complaint can
     * be blocked more than once, {@code sla_due_at} carries the accumulated effect, and the
     * individual spans belong in the log (section 4.3's argument, applied again).
     */
    private String resumeTheClock(Context context, Instant now) {
        Complaint complaint = context.complaint();
        Instant heldSince = complaint.getHoldStartedAt();
        complaint.resumeSlaClock(now);
        return AuditDetail.of("heldSince", Objects.toString(heldSince, null))
                .and("slaDueAt", Objects.toString(complaint.getSlaDueAt(), null))
                .json();
    }

    /** T11, and section 4.3's correction: the audit row is the docket's whole history. */
    private String recordDocket(Context context, Instant now) {
        String previous = context.complaint().getDocketNo();
        String corrected = context.payload().docketNo().trim();
        context.complaint().recordDocket(corrected, CurrentUser.idOrNull(), now);
        return AuditDetail.of("from", previous).and("to", corrected).json();
    }

    /** T8 — the work goes back to the pool. */
    private void releaseHolders(Task task) {
        assignments
                .findByTaskIdAndStatus(task.getId(), AssignmentStatus.ACTIVE)
                .forEach(TaskAssignment::release);
    }

    /** T14 — the +2, keyed to the work so a retry cannot double it (section 7.3). */
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
     * T17 — the reopen writes a compensating row; it never deletes the credit.
     *
     * <p>The attender's balance falls by 2 and both rows stay visible in their history. Bumping
     * the reopen count last is what makes a later re-resolution land on a <em>new</em> key
     * ({@code :r1}, {@code :r2}, …), so the uniqueness constraint keeps blocking duplicates without
     * ever blocking genuine repeat work (section 7.3).
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

    /** Everything a guard or a side effect is allowed to see, gathered once per transition. */
    private record Context(
            Complaint complaint, Task task, TaskAssignment assignment, TransitionPayload payload) {}
}
