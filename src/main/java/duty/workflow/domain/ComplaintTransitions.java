package duty.workflow.domain;

import static duty.workflow.domain.Complaint.ComplaintStatus.ASSIGNED;
import static duty.workflow.domain.Complaint.ComplaintStatus.AWAITING_CMS;
import static duty.workflow.domain.Complaint.ComplaintStatus.CLOSED;
import static duty.workflow.domain.Complaint.ComplaintStatus.INSPECTING;
import static duty.workflow.domain.Complaint.ComplaintStatus.IN_PROGRESS;
import static duty.workflow.domain.Complaint.ComplaintStatus.NEW;
import static duty.workflow.domain.Complaint.ComplaintStatus.ON_HOLD;
import static duty.workflow.domain.Complaint.ComplaintStatus.REJECTED;
import static duty.workflow.domain.Complaint.ComplaintStatus.RESOLVED;
import static duty.workflow.domain.Complaint.ComplaintStatus.TRIAGE;
import static duty.workflow.domain.ComplaintTransitions.TransitionActor.ASSIGNED_ATTENDER;
import static duty.workflow.domain.ComplaintTransitions.TransitionActor.ATTENDER_OR_MANAGER;
import static duty.workflow.domain.ComplaintTransitions.TransitionActor.MANAGER;
import static duty.workflow.domain.ComplaintTransitions.TransitionActor.SYSTEM;
import static duty.workflow.domain.ComplaintTransitions.TransitionGuard.DIFFERENT_ATTENDER;
import static duty.workflow.domain.ComplaintTransitions.TransitionGuard.DISPATCH_CANDIDATE;
import static duty.workflow.domain.ComplaintTransitions.TransitionGuard.DOCKET_FILED;
import static duty.workflow.domain.ComplaintTransitions.TransitionGuard.DOCKET_FORMAT;
import static duty.workflow.domain.ComplaintTransitions.TransitionGuard.NONE;
import static duty.workflow.domain.ComplaintTransitions.TransitionGuard.NO_DISPATCH_CANDIDATE;
import static duty.workflow.domain.ComplaintTransitions.TransitionGuard.REASON;
import static duty.workflow.domain.ComplaintTransitions.TransitionGuard.SEVEN_DAYS_SINCE_RESOLUTION;
import static duty.workflow.domain.ComplaintTransitions.TransitionGuard.TARGET_ATTENDER;
import duty.workflow.domain.Complaint.ComplaintStatus;
import java.util.List;

/**
 * The complaint transition table, as data, readable side by side with the design.
 *
 * <p>T2 and T3 share {@code (NEW, AUTO_DISPATCH)} and are told apart by their conditions, so a lookup
 * returns a list. There is no {@code (NEW, MANAGER_ASSIGN)} row: a complaint never stays {@code NEW},
 * so manual assignment is always from {@code TRIAGE} (T4).
 */
public final class ComplaintTransitions {

    private static final List<ComplaintTransition> TABLE =
            List.of(
                    // T1 `ingest` creates the complaint at NEW; it has no source state, so it is
                    // not a row of this table (see WorkflowEvent).
                    row(NEW, WorkflowEvent.AUTO_DISPATCH, ASSIGNED, SYSTEM, DISPATCH_CANDIDATE), // T2
                    row(NEW, WorkflowEvent.AUTO_DISPATCH, TRIAGE, SYSTEM, NO_DISPATCH_CANDIDATE), // T3
                    row(TRIAGE, WorkflowEvent.MANAGER_ASSIGN, ASSIGNED, MANAGER, TARGET_ATTENDER), // T4
                    row(TRIAGE, WorkflowEvent.MANAGER_REJECT, REJECTED, MANAGER, REASON), // T5
                    row(ASSIGNED, WorkflowEvent.MANAGER_REASSIGN, ASSIGNED, MANAGER, DIFFERENT_ATTENDER), // T6
                    row(ASSIGNED, WorkflowEvent.ATTENDER_START, INSPECTING, ASSIGNED_ATTENDER, NONE), // T7
                    row(ASSIGNED, WorkflowEvent.ATTENDER_RELEASE, TRIAGE, ASSIGNED_ATTENDER, REASON), // T8
                    row(INSPECTING, WorkflowEvent.DEFECT_CONFIRMED, AWAITING_CMS, ASSIGNED_ATTENDER, NONE), // T9
                    row(INSPECTING, WorkflowEvent.NO_DEFECT_FOUND, REJECTED, ASSIGNED_ATTENDER, REASON), // T10
                    row(AWAITING_CMS, WorkflowEvent.DOCKET_RECORDED, IN_PROGRESS, ASSIGNED_ATTENDER, DOCKET_FORMAT), // T11
                    // NOT IN SECTION 4.2's diagram, because it changes no state: section 4.3's
                    // correction. Attenders mistype numbers on phones, and the corrected value has
                    // to reach `docket_no` through the same audited path as the first one.
                    row(IN_PROGRESS, WorkflowEvent.DOCKET_RECORDED, IN_PROGRESS, ASSIGNED_ATTENDER, DOCKET_FORMAT),
                    row(IN_PROGRESS, WorkflowEvent.BLOCK, ON_HOLD, ATTENDER_OR_MANAGER, REASON), // T12
                    row(ON_HOLD, WorkflowEvent.UNBLOCK, IN_PROGRESS, ATTENDER_OR_MANAGER, NONE), // T13
                    row(IN_PROGRESS, WorkflowEvent.MARK_RESOLVED, RESOLVED, ASSIGNED_ATTENDER, DOCKET_FILED), // T14
                    row(RESOLVED, WorkflowEvent.MANAGER_CLOSE, CLOSED, MANAGER, NONE), // T15
                    row(RESOLVED, WorkflowEvent.AUTO_CLOSE, CLOSED, SYSTEM, SEVEN_DAYS_SINCE_RESOLUTION), // T16
                    row(RESOLVED, WorkflowEvent.MANAGER_REOPEN, IN_PROGRESS, MANAGER, REASON)); // T17

    private ComplaintTransitions() {}

    /** The rows for one state and event, in table order. Empty means the move is not allowed. */
    public static List<ComplaintTransition> matching(ComplaintStatus from, WorkflowEvent event) {
        return TABLE.stream().filter(row -> row.from() == from && row.event() == event).toList();
    }

    /** The whole table, for tests that walk every row. */
    public static List<ComplaintTransition> all() {
        return TABLE;
    }

    private static ComplaintTransition row(
            ComplaintStatus from,
            WorkflowEvent event,
            ComplaintStatus to,
            TransitionActor actor,
            TransitionGuard guard) {
        return new ComplaintTransition(from, event, to, actor, guard);
    }

    /**
     * One row of the transition table: from-state, event, to-state, who may do it, and its condition.
     * Side effects live in {@code WorkflowService}, since they need repositories.
     */
    public record ComplaintTransition(
            ComplaintStatus from,
            WorkflowEvent event,
            ComplaintStatus to,
            TransitionActor actor,
            TransitionGuard guard) {}

    /**
     * Who may make a transition.
     *
     * <p>Attender moves require holding an {@code ACTIVE} assignment, not just the attender role, so
     * nobody can act on someone else's task.
     */
    public enum TransitionActor {

        /** The system (a scheduled job or the dispatcher). */
        SYSTEM,

        /** The manager. */
        MANAGER,

        /** The attender holding an {@code ACTIVE} assignment on this complaint's task. */
        ASSIGNED_ATTENDER,

        /** Either the assigned attender or the manager (block and unblock). */
        ATTENDER_OR_MANAGER
    }

    /**
     * A transition's condition, with the message shown when it fails. Evaluated in
     * {@code WorkflowService}, since some checks need the database.
     */
    public enum TransitionGuard {

        /** No condition. */
        NONE("no guard"),

        /** T2: an attender was found. */
        DISPATCH_CANDIDATE("no attender was available for this complaint"),

        /** T3: no attender was found. */
        NO_DISPATCH_CANDIDATE("dispatch found a candidate, so this complaint does not need triage"),

        /** T4: an attender is given. */
        TARGET_ATTENDER("an attender to assign the work to is required"),

        /** T6: a different attender from the current one. */
        DIFFERENT_ATTENDER("this attender already holds the work"),

        /** A reason is required. */
        REASON("a reason is required"),

        /** T11: the docket number matches the format. */
        DOCKET_FORMAT("the docket number does not match the configured pattern"),

        /** T14: a docket is filed. No docket, no points. */
        DOCKET_FILED("this complaint was never filed with the Engineering Unit"),

        /** T16: seven days have passed since resolution. */
        SEVEN_DAYS_SINCE_RESOLUTION("seven days have not passed since this was resolved");

        private final String reason;

        TransitionGuard(String reason) {
            this.reason = reason;
        }

        /** The message shown when this condition fails. */
        public String reason() {
            return reason;
        }
    }

    /**
     * Everything that can happen to an existing complaint. The event says what was asked for; the
     * transition table decides the resulting state.
     *
     * <p>Creating a complaint (T1) is not here, because it has no prior state; {@code ComplaintIntake}
     * handles it.
     */
    public enum WorkflowEvent {

        /** T2/T3: auto-dispatch ran; its result decides between {@code ASSIGNED} and {@code TRIAGE}. */
        AUTO_DISPATCH,

        /** T4 — the manager picks a name. */
        MANAGER_ASSIGN,

        /** T5 — not a real complaint, or not ours. */
        MANAGER_REJECT,

        /** T6 — the manager moves live work to somebody else. */
        MANAGER_REASSIGN,

        /** T7 — the attender is on their way. */
        ATTENDER_START,

        /** T8 — the attender hands the work back. */
        ATTENDER_RELEASE,

        /** T9 — there is a defect, and the Engineering Unit needs a docket for it. */
        DEFECT_CONFIRMED,

        /** T10 — nothing wrong on site. */
        NO_DEFECT_FOUND,

        /** T11 — the number the Engineering Unit gave back. */
        DOCKET_RECORDED,

        /** T12 — waiting on parts or on access. */
        BLOCK,

        /** T13 — the block cleared. */
        UNBLOCK,

        /** T14 — the work is done and the +2 is due. */
        MARK_RESOLVED,

        /** T15 — the manager signs it off. */
        MANAGER_CLOSE,

        /** T16 — nobody signed it off and seven days passed. */
        AUTO_CLOSE,

        /** T17 — it was not fixed after all; the credit is reversed. */
        MANAGER_REOPEN
    }
}
