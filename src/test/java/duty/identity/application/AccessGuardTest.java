package duty.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import duty.identity.domain.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** Task ownership is checked, not just role: an attender cannot act on another attender's task. */
class AccessGuardTest {

    private static final long TASK = 412L;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static SignedInUser signedIn(long userId, UserRole role) {
        SignedInUser user = new SignedInUser(userId, "user" + userId + "@example.com", "User", role);
        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken(user, null, "ROLE_" + role.name()));
        return user;
    }

    @Test
    @DisplayName("an attender holding an ACTIVE assignment may act on the task")
    void allowsTheAssignedAttender() {
        signedIn(9L, UserRole.ATTENDER);
        AccessGuard guard = new AccessGuard((userId, taskId) -> userId == 9L && taskId == TASK);

        assertThat(guard.ownsTask(TASK)).isTrue();
    }

    @Test
    @DisplayName("another attender is refused, though the role is identical — E20, NOT_ASSIGNED")
    void refusesAnAttenderWithoutTheAssignment() {
        signedIn(10L, UserRole.ATTENDER);
        AccessGuard guard = new AccessGuard((userId, taskId) -> userId == 9L && taskId == TASK);

        assertThat(guard.ownsTask(TASK)).isFalse();
    }

    @Test
    @DisplayName("a manager holds no assignment either: the role is not a way round the check")
    void refusesAManagerWithoutAnAssignment() {
        signedIn(1L, UserRole.MANAGER);
        AccessGuard guard = new AccessGuard((userId, taskId) -> false);

        assertThat(guard.ownsTask(TASK)).isFalse();
    }

    @Test
    @DisplayName("nobody signed in owns nothing")
    void refusesAnAnonymousCaller() {
        AccessGuard guard = new AccessGuard((userId, taskId) -> true);

        assertThat(guard.ownsTask(TASK)).isFalse();
    }
}
