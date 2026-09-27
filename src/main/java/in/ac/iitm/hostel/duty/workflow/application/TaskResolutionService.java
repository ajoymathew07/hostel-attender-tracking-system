package in.ac.iitm.hostel.duty.workflow.application;

import in.ac.iitm.hostel.duty.audit.application.AuditDetail;
import in.ac.iitm.hostel.duty.audit.application.AuditEntry;
import in.ac.iitm.hostel.duty.audit.application.AuditTrail;
import in.ac.iitm.hostel.duty.identity.application.CurrentUser;
import in.ac.iitm.hostel.duty.identity.application.SignedInUser;
import in.ac.iitm.hostel.duty.ledger.application.PointsLedger;
import in.ac.iitm.hostel.duty.workflow.domain.AssignmentStatus;
import in.ac.iitm.hostel.duty.workflow.domain.Complaint;
import in.ac.iitm.hostel.duty.workflow.domain.ComplaintStatus;
import in.ac.iitm.hostel.duty.workflow.domain.Task;
import in.ac.iitm.hostel.duty.workflow.domain.TaskAssignment;
import in.ac.iitm.hostel.duty.workflow.domain.TaskStatus;
import in.ac.iitm.hostel.duty.workflow.infrastructure.TaskAssignmentRepository;
import in.ac.iitm.hostel.duty.workflow.infrastructure.TaskRepository;
import java.time.Instant;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Filing the docket and resolving the task — the end of the walking skeleton (transitions T11 and
 * T14).
 *
 * <p><strong>Complaint status moves only the two steps this flow needs.</strong> Recording the
 * docket puts the complaint {@code IN_PROGRESS} and resolving puts it {@code RESOLVED}, because
 * otherwise the screen would show a complaint jumping from {@code NEW} to nothing at all. The
 * guarded transition table itself — every pair, every guard, {@code WorkflowService.transition}
 * as the single write path — is PR 11's, and building a general mechanism here would be writing
 * that PR early (section 4.1).
 *
 * <p><strong>Ownership, not role.</strong> Neither action has a {@code hasRole('ATTENDER')} on it:
 * "any attender" is precisely the wrong answer, because it would let one attender resolve
 * another's work and collect the +2 (section 11.3). {@link #recordDocket} uses the existing
 * {@code @access.ownsTask}, which means an {@code ACTIVE} assignment. {@link #resolve} cannot: it
 * has to load that same assignment in order to complete it, so the check and the load are one
 * query, and it must accept the caller's own already-{@code COMPLETED} row as a replay rather than
 * refuse it (section 7.4).
 */
@Service
@Transactional
public class TaskResolutionService {

    private static final String ENTITY_TYPE = "complaint";

    private final TaskRepository tasks;
    private final TaskAssignmentRepository assignments;
    private final PointsLedger ledger;
    private final AuditTrail audit;

    /**
     * OQ-1: there is no CMS API (section 1.8), so only the format can be checked, and the format
     * is configuration so that a wrong guess costs a property change rather than a redeploy.
     */
    private final Pattern docketPattern;

    TaskResolutionService(
            TaskRepository tasks,
            TaskAssignmentRepository assignments,
            PointsLedger ledger,
            AuditTrail audit,
            @Value("${workflow.docket-pattern}") String docketPattern) {
        this.tasks = tasks;
        this.assignments = assignments;
        this.ledger = ledger;
        this.audit = audit;
        this.docketPattern = Pattern.compile(docketPattern);
    }

    /**
     * Transition T11 — the attender files the complaint with the Engineering Unit and types the
     * number back in.
     *
     * <p>A correction overwrites the number in place and the audit row keeps both values, which is
     * why there is no docket history table (section 4.3, ADR-012).
     */
    @PreAuthorize("@access.ownsTask(#taskId)")
    public void recordDocket(long taskId, String docketNo) {
        String trimmed = docketNo == null ? "" : docketNo.trim();
        if (!docketPattern.matcher(trimmed).matches()) {
            throw new InvalidDocketException(trimmed);
        }
        Instant now = Instant.now();
        Complaint complaint = tasks.findById(taskId).orElseThrow().getComplaint();
        String previous = complaint.getDocketNo();

        complaint.recordDocket(trimmed, CurrentUser.idOrNull(), now);
        complaint.moveTo(ComplaintStatus.IN_PROGRESS, now);

        audit.record(
                AuditEntry.change(
                        ENTITY_TYPE,
                        complaint.getId(),
                        "DOCKET_RECORDED",
                        AuditDetail.of("from", previous).and("to", trimmed).json(),
                        CurrentUser.idOrNull(),
                        CurrentUser.roleOrNull()));
    }

    /**
     * Transition T14 — the work is done: credit the points, complete the assignment and the task,
     * and stamp the complaint {@code RESOLVED}.
     *
     * <p>The ledger decides whether anything happens. If the credit is already there this is a
     * replay of the same tap — the attender lost signal and the service worker sent it again
     * (section 7.4) — so the method returns the same success and touches nothing. That matters for
     * more than the points: re-stamping {@code resolved_at} would silently restart the 7-day
     * window on the resident's contact details (section 11.4).
     */
    public ResolvedTask resolve(long taskId) {
        TaskAssignment mine = myAssignment(taskId);
        Task task = mine.getTask();
        Complaint complaint = task.getComplaint();
        if (!complaint.hasDocket()) {
            throw new DocketRequiredException(taskId);
        }

        Instant now = Instant.now();
        boolean credited =
                ledger.creditForResolve(mine.getAttenderId(), task.getId(), task.getPointValue(), now);
        if (credited) {
            ComplaintStatus before = complaint.getStatus();
            mine.complete();
            task.moveTo(TaskStatus.COMPLETED);
            // The only path that stamps `resolved_at`, which starts both the 7-day PII window
            // (section 11.4) and the 7-day auto-close clock (T16).
            complaint.moveTo(ComplaintStatus.RESOLVED, now);
            audit.record(
                    AuditEntry.transition(
                            ENTITY_TYPE,
                            complaint.getId(),
                            "MARK_RESOLVED",
                            before.name(),
                            ComplaintStatus.RESOLVED.name(),
                            CurrentUser.idOrNull(),
                            CurrentUser.roleOrNull()));
        }

        return new ResolvedTask(
                complaint.getPublicRef(),
                credited ? task.getPointValue() : 0,
                ledger.myPeriodTotal(),
                !credited);
    }

    /**
     * The caller's own hold on this task.
     *
     * <p>A {@code RELEASED} row records that this attender once held the task, which is exactly
     * the person who must no longer act on it (E20). An absent row is a stranger. Both are
     * {@code AccessDeniedException}, so the refusal reaches the browser as the same 403 that
     * {@code @access.ownsTask} produces, with no handler of its own.
     */
    private TaskAssignment myAssignment(long taskId) {
        long attenderId =
                CurrentUser.get()
                        .map(SignedInUser::getUserId)
                        .orElseThrow(() -> new AccessDeniedException("nobody is signed in"));
        return assignments
                .findByTaskIdAndAttenderId(taskId, attenderId)
                .filter(assignment -> assignment.getStatus() != AssignmentStatus.RELEASED)
                .orElseThrow(() -> new AccessDeniedException("this task is not yours to resolve"));
    }
}
