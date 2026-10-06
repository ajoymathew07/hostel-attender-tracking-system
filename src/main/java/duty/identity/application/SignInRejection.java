package duty.identity.application;

/**
 * Why a sign-in was refused. Recorded in the audit log, but the user always sees the same
 * {@code NOT_PROVISIONED} message so the login page cannot reveal who has an account.
 */
public enum SignInRejection {

    /** The {@code email_verified} claim was absent or false, so the address proves nothing. */
    EMAIL_NOT_VERIFIED,

    /** A valid Google identity with no row on the allowlist. */
    NOT_PROVISIONED,

    /** On the allowlist, but deactivated. */
    DEACTIVATED
}
