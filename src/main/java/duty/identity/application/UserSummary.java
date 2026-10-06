package duty.identity.application;

import duty.identity.domain.AppUser;
import duty.identity.domain.UserRole;
import java.time.Instant;

/**
 * One allowlist row as other modules and views see it, so the {@code AppUser} entity stays inside
 * this module.
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
