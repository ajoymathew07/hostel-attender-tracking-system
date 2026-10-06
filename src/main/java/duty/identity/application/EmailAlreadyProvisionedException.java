package duty.identity.application;

/**
 * That Google address is already on the allowlist. Checked first for a friendly message; the unique
 * constraint is the real guarantee.
 */
public class EmailAlreadyProvisionedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String email;

    public EmailAlreadyProvisionedException(String email) {
        super(email + " is already on the allowlist");
        this.email = email;
    }

    public String getEmail() {
        return email;
    }
}
