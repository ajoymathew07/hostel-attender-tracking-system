package duty.workflow.application;

/**
 * The move is allowed from this state, but its condition is not met (e.g. a missing reason).
 *
 * <p>Separate from {@link IllegalTransitionException} so the message can tell the user which one
 * happened.
 */
public class GuardFailedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public GuardFailedException(String reason) {
        super(reason);
    }
}
