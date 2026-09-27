package in.ac.iitm.hostel.duty.workflow.application;

import in.ac.iitm.hostel.duty.workflow.domain.ComplaintStatus;
import in.ac.iitm.hostel.duty.workflow.domain.WorkflowEvent;

/**
 * The {@code (state, event)} pair is absent from the transition table (section 4.1, step 2).
 *
 * <p>Nothing has been written when this is thrown: the table is consulted before any side effect
 * runs, which is what makes "a complaint cannot be resolved twice" a property of the machine
 * rather than of whoever wrote the screen.
 *
 * <p>In practice a user meets this from a stale tab — two phones open on the same task, one of
 * them a minute behind — so the message names both ends of the refusal (section 11.6).
 */
public class IllegalTransitionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public IllegalTransitionException(ComplaintStatus from, WorkflowEvent event) {
        super("A complaint that is " + from + " cannot be moved by " + event);
    }
}
