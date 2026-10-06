package duty.identity.application;

import java.util.Objects;

/**
 * The result of one sign-in decision: admitted, or refused with a reason.
 *
 * <p>Returned rather than thrown so the refusal's audit row commits. The web layer turns a refusal into
 * Spring Security's exception.
 */
public record SignInOutcome(SignedInUser user, SignInRejection rejection, String email) {

    public static SignInOutcome admitted(SignedInUser user) {
        return new SignInOutcome(Objects.requireNonNull(user, "user"), null, user.getEmail());
    }

    public static SignInOutcome refused(SignInRejection rejection, String email) {
        return new SignInOutcome(null, Objects.requireNonNull(rejection, "rejection"), email);
    }

    public boolean admitted() {
        return user != null;
    }
}
