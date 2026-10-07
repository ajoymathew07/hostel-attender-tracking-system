package duty.identity.application;

import duty.audit.application.AuditTrail.AuditDetail;
import duty.audit.application.AuditTrail.AuditEntry;
import duty.audit.application.AuditTrail;
import duty.identity.application.CurrentUser.SignedInUser;
import duty.identity.application.UserProvisioningService.EmailAddresses;
import duty.identity.domain.AppUser;
import duty.identity.infrastructure.AppUserRepository;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The allowlist check: admits a verified Google email only if it matches an active {@code app_user}
 * row.
 *
 * <p>Nothing is ever created here, an unverified address is never matched, and both outcomes are
 * audited. No passwords exist anywhere in the system.
 */
@Service
public class SignInService {

    private static final String ENTITY_TYPE = "app_user";

    /**
     * Stands in for the entity id when a stranger is refused, since {@code entity_id} cannot be null.
     */
    private static final long NO_SUCH_USER = 0L;

    private final AppUserRepository users;
    private final AuditTrail audit;

    SignInService(AppUserRepository users, AuditTrail audit) {
        this.users = users;
        this.audit = audit;
    }

    /**
     * Decides one sign-in. Writes {@code last_login_at} on success and an audit row either way. Never throws
     * on a refusal.
     */
    @Transactional
    public SignInOutcome admit(OidcIdentity identity) {
        String email = identity.email() == null ? null : AppUser.normalizeEmail(identity.email());

        if (email == null) {
            return refuse(SignInRejection.NOT_PROVISIONED, "", NO_SUCH_USER);
        }
        if (!identity.emailVerified()) {
            return refuse(SignInRejection.EMAIL_NOT_VERIFIED, email, NO_SUCH_USER);
        }
        // Checked on the raw address, before lower-casing: some Unicode letters lower-case to
        // ASCII and could match someone else's row (see EmailAddresses).
        if (!EmailAddresses.isAscii(identity.email())) {
            return refuse(SignInRejection.NOT_PROVISIONED, email, NO_SUCH_USER);
        }

        Optional<AppUser> found = users.findByEmail(email);
        if (found.isEmpty()) {
            return refuse(SignInRejection.NOT_PROVISIONED, email, NO_SUCH_USER);
        }

        AppUser user = found.get();
        if (!user.isActive()) {
            return refuse(SignInRejection.DEACTIVATED, email, user.getId());
        }

        user.recordLogin(Instant.now());
        audit.record(
                AuditEntry.change(
                        ENTITY_TYPE,
                        user.getId(),
                        "SIGN_IN",
                        json(email, null),
                        user.getId(),
                        user.getRole().name()));

        return SignInOutcome.admitted(
                new SignedInUser(user.getId(), user.getEmail(), user.getDisplayName(), user.getRole()));
    }

    /** No actor on a refusal, because nobody authenticated. */
    private SignInOutcome refuse(SignInRejection rejection, String email, Long entityId) {
        audit.record(
                AuditEntry.change(
                        ENTITY_TYPE, entityId, "SIGN_IN_REJECTED", json(email, rejection), null, null));
        return SignInOutcome.refused(rejection, email);
    }

    /** A two-field JSON object, escaped because the address came from outside. */
    private static String json(String email, SignInRejection rejection) {
        return rejection == null
                ? AuditDetail.of("email", email).json()
                : AuditDetail.of("email", email).and("reason", rejection.name()).json();
    }

    /**
     * The only two things the allowlist uses from Google: the email address and whether Google verified
     * it. Name and role come from {@code app_user}, never from Google.
     */
    public record OidcIdentity(String email, boolean emailVerified) {}

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
}
