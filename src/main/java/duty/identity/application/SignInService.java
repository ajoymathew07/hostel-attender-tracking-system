package duty.identity.application;

/**
 * The identity module's published sign-in surface (architecture section 2.4, rule 3).
 *
 * <p>This is <strong>the</strong> access-control boundary of the system (section 11.2). Google
 * establishes who someone is and has no opinion on whether they may use this application; that
 * decision is made here, in one place, against {@code app_user}.
 */
public interface SignInService {

    /**
     * Decides whether a verified Google identity may sign in, records the attempt either way, and
     * never creates a user: there is no self-registration, so an unknown address is refused rather
     * than provisioned (architecture section 2.3).
     */
    SignInOutcome admit(OidcIdentity identity);
}
