package duty.identity.application;

/**
 * Why a Google sign-in was refused.
 *
 * <p>The distinction is recorded in the audit log (section 11.7) but is deliberately <em>not</em>
 * shown to the person who was refused: every rejection surfaces as the single {@code
 * NOT_PROVISIONED} error of section 9.7. Telling an unknown caller "that address exists but is
 * deactivated" would turn the login page into a directory of who works here.
 */
public enum SignInRejection {

    /** The {@code email_verified} claim was absent or false, so the address proves nothing. */
    EMAIL_NOT_VERIFIED,

    /** A valid Google identity with no row on the allowlist — the common case (E20a). */
    NOT_PROVISIONED,

    /** On the allowlist, but {@code active = false}: a departure, or a lost phone (E20b). */
    DEACTIVATED
}
