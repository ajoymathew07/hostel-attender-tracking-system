package duty.identity.application;

/**
 * The only two things the allowlist uses from Google: the email address and whether Google verified
 * it. Name and role come from {@code app_user}, never from Google.
 */
public record OidcIdentity(String email, boolean emailVerified) {}
