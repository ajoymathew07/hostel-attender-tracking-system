package duty.identity.application;

import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Who is acting, read from the security context so it need not be passed through every method.
 *
 * <p>Empty when nobody is signed in: scheduled jobs and ingestion act as the system.
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
