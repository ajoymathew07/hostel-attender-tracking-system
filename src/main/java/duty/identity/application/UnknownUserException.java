package duty.identity.application;

/** No user with that id: a stale screen or an edited form post. */
public class UnknownUserException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public UnknownUserException(long userId) {
        super("No user with id " + userId);
    }
}
