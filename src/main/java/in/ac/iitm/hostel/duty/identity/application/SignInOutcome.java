package in.ac.iitm.hostel.duty.identity.application;

import java.util.Objects;

/**
 * The result of one allowlist decision: admitted, or refused with a reason.
 *
 * <p>A rejection is a returned value rather than a thrown exception on purpose. The refusal must be
 * <em>audited</em> (section 11.7), and the audit row is written by a {@code BEFORE_COMMIT} listener
 * inside the transaction that recorded it — throwing from inside that transaction would roll the
 * row back and the log would lose exactly the events a security log exists for. So the decision
 * commits, and the adapter at the edge ({@code AllowlistOidcUserService}) turns a refusal into the
 * {@code OAuth2AuthenticationException} Spring Security expects.
 *
 * @param user the admitted principal, or {@code null} when refused
 * @param rejection why it was refused, or {@code null} when admitted
 * @param email the normalised address the decision was made about
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
