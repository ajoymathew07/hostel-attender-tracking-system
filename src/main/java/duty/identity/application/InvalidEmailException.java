package duty.identity.application;

/** Not an address the allowlist will accept. A bad address would silently lock that person out. */
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
