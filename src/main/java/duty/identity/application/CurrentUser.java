package duty.identity.application;

import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Who is acting, read from the security context.
 *
 * <p>Every audited change needs an actor and every ownership check needs a user id (sections 11.3,
 * 11.7). Threading the principal through every method signature from the controller down would be
 * noise, so it is read here — the same shape as {@code CorrelationId} in the audit module.
 *
 * <p>Returns {@link Optional#empty()} rather than throwing when nobody is signed in: scheduled
 * jobs and ingestion runs legitimately act with no principal, and they record {@code actor_id =
 * null}, meaning the system acted.
 */
public final class CurrentUser {

    private CurrentUser() {}

    /** The signed-in principal, or empty for an anonymous request or a background job. */
    public static Optional<SignedInUser> get() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        Object principal = authentication.getPrincipal();
        return principal instanceof SignedInUser user ? Optional.of(user) : Optional.empty();
    }

    /** The acting user's id, or {@code null} for the system — the shape {@code AuditEntry} wants. */
    public static Long idOrNull() {
        return get().map(SignedInUser::getUserId).orElse(null);
    }

    /** The acting user's role name, or {@code null} for the system. */
    public static String roleOrNull() {
        return get().map(user -> user.getRole().name()).orElse(null);
    }
}
