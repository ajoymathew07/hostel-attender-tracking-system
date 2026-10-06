package duty.identity.application;

import duty.identity.domain.AppUser;
import duty.identity.domain.UserRole;
import java.time.Instant;

/**
 * One row of the allowlist as the rest of the system sees it.
 *
 * <p>A record rather than the {@code AppUser} entity, because {@code identity.domain} is internal
 * to this module (architecture section 2.4, rule 3) and because a detached entity handed to a view
 * is a stale copy that invites a write through the back door.
 */
public record UserSummary(
        Long id,
        String displayName,
        String email,
        UserRole role,
        boolean active,
        Instant lastLoginAt,
        Instant createdAt) {

    static UserSummary of(AppUser user) {
        return new UserSummary(
                user.getId(),
                user.getDisplayName(),
                user.getEmail(),
                user.getRole(),
                user.isActive(),
                user.getLastLoginAt(),
                user.getCreatedAt());
    }
}
