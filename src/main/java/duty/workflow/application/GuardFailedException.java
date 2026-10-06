package duty.workflow.application;

/**
 * The move is legal but its precondition is not met (section 4.1, step 3).
 *
 * <p>Distinct from {@link IllegalTransitionException} because the two mean different things to the
 * person in front of the screen: an illegal pair means "that is not a thing you can do from here",
 * a failed guard means "you can, once you supply a reason" — and the message says which.
 *
 * <p>The docket gate has its own subclass, {@link DocketRequiredException}, because it is the one
 * refusal with a specific sentence of its own on the attender's phone.
 */
public class GuardFailedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public GuardFailedException(String reason) {
        super(reason);
    }
}
