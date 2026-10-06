package duty.identity.application;

/**
 * That is not an address this system will accept as an allowlist key.
 *
 * <p>Checked in the service rather than only in the form, because the allowlist key is the <em>only
 * thing that lets somebody in</em>: an unchecked value silently locks a real person out — their
 * Google address no longer matches any row — and the mistake is invisible until they next try to
 * sign in.
 */
public class InvalidEmailException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String email;

    public InvalidEmailException(String email) {
        super("Not a usable Google address: " + email);
        this.email = email;
    }

    public String getEmail() {
        return email;
    }
}
