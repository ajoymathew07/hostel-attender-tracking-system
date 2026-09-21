package in.ac.iitm.hostel.duty.identity.application;

/**
 * Refuses the operation that would leave the system with no manager.
 *
 * <p>ADR-002 answers "what if the manager loses access" with <em>a row, not a mechanism</em>: two
 * {@code MANAGER} rows exist from day one so that either can administer if the other becomes
 * unusable. Nothing enforced that, so the provisioning screen could switch off the last one — and
 * with no password anywhere in the schema there is then no way back in short of direct database
 * access. The seeded second manager is a {@code .invalid} placeholder until someone replaces it, so
 * on day one this was a single click.
 */
public class LastManagerException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public LastManagerException() {
        super("The last active manager cannot be deactivated or demoted");
    }
}
