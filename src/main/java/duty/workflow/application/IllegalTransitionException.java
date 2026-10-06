package duty.workflow.application;

import duty.workflow.domain.ComplaintStatus;
import duty.workflow.domain.WorkflowEvent;

/**
 * The move is not allowed from the complaint's current state. Thrown before anything is written.
 *
 * <p>Usually caused by a stale screen, e.g. the same task open on two phones.
 */
public class IllegalTransitionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public IllegalTransitionException(ComplaintStatus from, WorkflowEvent event) {
        super("A complaint that is " + from + " cannot be moved by " + event);
    }
}
