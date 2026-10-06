package duty.identity.application;

/** No {@code app_user} row with that id — a stale screen, or a hand-edited form post. */
public class UnknownUserException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public UnknownUserException(long userId) {
        super("No user with id " + userId);
    }
}
