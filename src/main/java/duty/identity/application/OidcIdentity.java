package duty.identity.application;

/**
 * Everything the allowlist is willing to hear from Google.
 *
 * <p>Deliberately two fields. Google tells us <em>who</em> someone is; the display name and the
 * role are ours, held in {@code app_user} and set by a manager at provisioning time (section 11.2).
 * Nothing Google asserts beyond the verified address is allowed to influence any decision, so
 * nothing else is carried across the boundary in the first place.
 *
 * @param email the {@code email} claim, unnormalised as received
 * @param emailVerified the {@code email_verified} claim; a self-asserted address is never matched
 */
public record OidcIdentity(String email, boolean emailVerified) {}
