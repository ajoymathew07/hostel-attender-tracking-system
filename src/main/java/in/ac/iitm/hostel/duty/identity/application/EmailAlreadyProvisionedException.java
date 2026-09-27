package in.ac.iitm.hostel.duty.identity.application;

/**
 * That Google address is already on the allowlist.
 *
 * <p>Checked before the insert so the manager gets a sentence rather than a constraint violation,
 * but {@code uq_user_email} remains the authority: the check and the constraint are not
 * alternatives, and only the constraint holds under a concurrent insert.
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
