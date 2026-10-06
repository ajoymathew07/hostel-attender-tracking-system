package duty.identity.application;

import duty.audit.application.AuditDetail;
import duty.audit.application.AuditEntry;
import duty.audit.application.AuditTrail;
import duty.identity.domain.AppUser;
import duty.identity.infrastructure.AppUserRepository;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The allowlist: resolves a verified Google email against {@code app_user} and refuses anything
 * without a matching active row (section 11.2, ADR-002).
 *
 * <p>Three properties of this class are the security control, and each is tested directly:
 *
 * <ul>
 *   <li><strong>Nothing is created.</strong> An unknown address is refused; it never becomes a row.
 *       There is no self-registration path in this system at all (architecture section 2.3).
 *   <li><strong>The address must be verified by Google.</strong> A self-asserted {@code email}
 *       claim is not matched against anything, so an attacker who can set their own profile email
 *       cannot borrow an allowlisted address.
 *   <li><strong>Both outcomes are audited</strong>, a refusal carrying the address that was refused
 *       (section 11.7) — the log of attempts by people who are not allowed in is the more
 *       interesting half.
 * </ul>
 *
 * <p>No credential is read, compared or stored here, because none exists anywhere in the schema.
 */
@Service
class AllowlistSignInService implements SignInService {

    private static final String ENTITY_TYPE = "app_user";

    /**
     * {@code audit_event.entity_id} is {@code NOT NULL}, and a refused sign-in by a stranger names
     * no row. Zero stands for "no such user" — the address is in {@code detail}, which is the field
     * that actually identifies the attempt.
     */
    private static final long NO_SUCH_USER = 0L;

    private final AppUserRepository users;
    private final AuditTrail audit;

    AllowlistSignInService(AppUserRepository users, AuditTrail audit) {
        this.users = users;
        this.audit = audit;
    }

    /**
     * Transactional because it writes: {@code last_login_at} on success, and an audit row either
     * way. It does not throw on a refusal — see {@link SignInOutcome} for why the audit row would
     * otherwise be rolled back along with it.
     */
    @Override
    @Transactional
    public SignInOutcome admit(OidcIdentity identity) {
        String email = identity.email() == null ? null : AppUser.normalizeEmail(identity.email());

        if (email == null) {
            return refuse(SignInRejection.NOT_PROVISIONED, "", NO_SUCH_USER);
        }
        if (!identity.emailVerified()) {
            return refuse(SignInRejection.EMAIL_NOT_VERIFIED, email, NO_SUCH_USER);
        }
        // Tested on what Google sent, NOT on `email`: normalisation is where the damage is done.
        // Lower-casing is not injective over Unicode — U+212A KELVIN SIGN folds to 'k', so
        // "Kumar@…" normalises to the ASCII string "kumar@…" and would match a row it is not
        // (see EmailAddresses). Nothing this system stores is non-ASCII, so refusing loses nobody.
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

    /**
     * {@code actorId} is deliberately null even when the address matched a deactivated row: nobody
     * authenticated, so there is no actor. The row that was refused is named by {@code entityId}.
     */
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
}
