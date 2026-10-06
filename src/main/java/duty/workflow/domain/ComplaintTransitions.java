package duty.workflow.domain;

import static duty.workflow.domain.ComplaintStatus.ASSIGNED;
import static duty.workflow.domain.ComplaintStatus.AWAITING_CMS;
import static duty.workflow.domain.ComplaintStatus.CLOSED;
import static duty.workflow.domain.ComplaintStatus.INSPECTING;
import static duty.workflow.domain.ComplaintStatus.IN_PROGRESS;
import static duty.workflow.domain.ComplaintStatus.NEW;
import static duty.workflow.domain.ComplaintStatus.ON_HOLD;
import static duty.workflow.domain.ComplaintStatus.REJECTED;
import static duty.workflow.domain.ComplaintStatus.RESOLVED;
import static duty.workflow.domain.ComplaintStatus.TRIAGE;
import static duty.workflow.domain.TransitionActor.ASSIGNED_ATTENDER;
import static duty.workflow.domain.TransitionActor.ATTENDER_OR_MANAGER;
import static duty.workflow.domain.TransitionActor.MANAGER;
import static duty.workflow.domain.TransitionActor.SYSTEM;
import static duty.workflow.domain.TransitionGuard.DIFFERENT_ATTENDER;
import static duty.workflow.domain.TransitionGuard.DISPATCH_CANDIDATE;
import static duty.workflow.domain.TransitionGuard.DOCKET_FILED;
import static duty.workflow.domain.TransitionGuard.DOCKET_FORMAT;
import static duty.workflow.domain.TransitionGuard.NONE;
import static duty.workflow.domain.TransitionGuard.NO_DISPATCH_CANDIDATE;
import static duty.workflow.domain.TransitionGuard.REASON;
import static duty.workflow.domain.TransitionGuard.SEVEN_DAYS_SINCE_RESOLUTION;
import static duty.workflow.domain.TransitionGuard.TARGET_ATTENDER;

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
}
