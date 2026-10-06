package duty.identity.application;

/**
 * Refuses a change that would leave no active manager.
 *
 * <p>Why: there are no passwords, so with no manager nobody could get back in without editing the
 * database.
 */
public class LastManagerException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public LastManagerException() {
        super("The last active manager cannot be deactivated or demoted");
    }
}
