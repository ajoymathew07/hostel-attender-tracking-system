package duty.identity.application;

import duty.audit.application.AuditTrail.AuditDetail;
import duty.audit.application.AuditTrail.AuditEntry;
import duty.audit.application.AuditTrail;
import duty.identity.domain.AppUser.UserRole;
import duty.identity.domain.AppUser;
import duty.identity.infrastructure.AppUserRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages the allowlist: add, deactivate, reactivate, change role, change email.
 *
 * <p>Every method is manager-only except {@link #activeAttenderIds()}, which dispatch calls with
 * nobody signed in. There is no delete: deactivating keeps the row, so the ledger and audit log still
 * name a real person. Each change is audited.
 */
@Service
@Transactional
public class UserProvisioningService {

    private static final String ENTITY_TYPE = "app_user";

    private final AppUserRepository users;
    private final AuditTrail audit;

    UserProvisioningService(AppUserRepository users, AuditTrail audit) {
        this.users = users;
        this.audit = audit;
    }

    /** Everyone on the allowlist, active or not. */
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('MANAGER')")
    public List<UserSummary> listUsers() {
        return users.findAll().stream()
                .sorted(
                        Comparator.comparing(AppUser::getRole)
                                .thenComparing(AppUser::getDisplayName, String.CASE_INSENSITIVE_ORDER))
                .map(UserSummary::of)
                .toList();
    }

    /**
     * Ids of every active attender, lowest first: dispatch's candidates.
     *
     * <p>Not manager-only because dispatch runs with nobody signed in. It returns ids only, so nothing
     * private is exposed.
     */
    @Transactional(readOnly = true)
    public List<Long> activeAttenderIds() {
        return users.findByRoleAndActiveIsTrueOrderByDisplayNameAsc(UserRole.ATTENDER).stream()
                .map(AppUser::getId)
                .sorted()
                .toList();
    }

    /** Active attenders a manager may assign work to, by name. */
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('MANAGER')")
    public List<UserSummary> listAssignableAttenders() {
        return users.findByRoleAndActiveIsTrueOrderByDisplayNameAsc(UserRole.ATTENDER).stream()
                .map(UserSummary::of)
                .toList();
    }

    /** Adds a Google address to the allowlist: the only way an account is created. */
    @PreAuthorize("hasRole('MANAGER')")
    public UserSummary provision(String displayName, String email, UserRole role) {
        String normalised = EmailAddresses.requireAllowlistAddress(email);
        if (users.existsByEmail(normalised)) {
            throw new EmailAlreadyProvisionedException(normalised);
        }

        AppUser user =
                users.saveAndFlush(
                        new AppUser(displayName, role, normalised, Instant.now(), CurrentUser.idOrNull()));
        record(user, "USER_PROVISIONED", AuditDetail.of("email", normalised).and("role", role.name()).json());
        return UserSummary.of(user);
    }

    /** Blocks the user's next sign-in and keeps the row. */
    @PreAuthorize("hasRole('MANAGER')")
    public UserSummary deactivate(long userId) {
        AppUser user = require(userId);
        requireAnotherManagerRemains(user, UserRole.ATTENDER);
        user.deactivate();
        return saveAndRecord(user, "USER_DEACTIVATED", AuditDetail.of("active", false).json());
    }

    /** Puts a deactivated user back on the allowlist. */
    @PreAuthorize("hasRole('MANAGER')")
    public UserSummary reactivate(long userId) {
        AppUser user = require(userId);
        user.reactivate();
        return saveAndRecord(user, "USER_REACTIVATED", AuditDetail.of("active", true).json());
    }

    /** Changes a user's role; it takes effect at their next sign-in. */
    @PreAuthorize("hasRole('MANAGER')")
    public UserSummary changeRole(long userId, UserRole role) {
        AppUser user = require(userId);
        requireAnotherManagerRemains(user, role);
        UserRole previous = user.getRole();
        user.changeRole(role);
        return saveAndRecord(
                user, "USER_ROLE_CHANGED", AuditDetail.of("from", previous.name()).and("to", role.name()).json());
    }

    /** Points the allowlist at a different Google account: the recovery path for a lost account. */
    @PreAuthorize("hasRole('MANAGER')")
    public UserSummary changeEmail(long userId, String email) {
        AppUser user = require(userId);
        String normalised = EmailAddresses.requireAllowlistAddress(email);
        String previous = user.getEmail();
        if (!previous.equals(normalised) && users.existsByEmail(normalised)) {
            throw new EmailAlreadyProvisionedException(normalised);
        }
        user.changeEmail(normalised);
        return saveAndRecord(
                user, "USER_EMAIL_CHANGED", AuditDetail.of("from", previous).and("to", normalised).json());
    }

    private AppUser require(long userId) {
        return users.findById(userId).orElseThrow(() -> new UnknownUserException(userId));
    }

    /**
     * Throws {@link LastManagerException} if the change would remove the last active manager.
     * Deactivation passes {@code ATTENDER}, since it removes a manager just as a demotion does.
     */
    private void requireAnotherManagerRemains(AppUser user, UserRole roleAfter) {
        boolean wasAManager = user.getRole() == UserRole.MANAGER && user.isActive();
        boolean stillAManager = roleAfter == UserRole.MANAGER;
        if (wasAManager && !stillAManager && users.countByRoleAndActiveIsTrue(UserRole.MANAGER) <= 1) {
            throw new LastManagerException();
        }
    }

    private UserSummary saveAndRecord(AppUser user, String eventType, String detail) {
        AppUser saved = users.saveAndFlush(user);
        record(saved, eventType, detail);
        return UserSummary.of(saved);
    }

    private void record(AppUser user, String eventType, String detail) {
        audit.record(
                AuditEntry.change(
                        ENTITY_TYPE,
                        user.getId(),
                        eventType,
                        detail,
                        CurrentUser.idOrNull(),
                        CurrentUser.roleOrNull()));
    }

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

    /**
     * Validation for allowlist email addresses.
     *
     * <p>{@link #requireAllowlistAddress} throws, so a manager is told about a typo. {@link #isAscii}
     * returns a boolean because the sign-in path must not throw (a throw would roll back the audit row).
     * Why ASCII only: lower-casing some Unicode characters (e.g. the Kelvin sign) produces a plain
     * {@code k}, which could match someone else's address.
     */
    static final class EmailAddresses {

        /**
         * Stricter than RFC 5321 on purpose: no quoted local parts, spaces or backslashes. A Google account
         * never needs them.
         */
        private static final Pattern ALLOWLIST_ADDRESS =
                Pattern.compile("[A-Za-z0-9!#$%&'*+/=?^_`{|}~.-]{1,64}@[A-Za-z0-9.-]{1,185}\\.[A-Za-z]{2,}");

        /** {@code app_user.email} is {@code VARCHAR(255)}. */
        private static final int MAX_LENGTH = 255;

        private EmailAddresses() {}

        /**
         * The normalised address, or {@link InvalidEmailException} if it is not acceptable as an allowlist
         * key.
         */
        static String requireAllowlistAddress(String raw) {
            if (raw == null || raw.isBlank()) {
                throw new InvalidEmailException("");
            }
            // Checked before normalising, because normalising is what folds a non-ASCII character onto
            // an ASCII one; afterwards the evidence is gone and the pattern below would be satisfied.
            if (!isAscii(raw)) {
                throw new InvalidEmailException(raw.trim());
            }
            String normalised = AppUser.normalizeEmail(raw);
            if (normalised.length() > MAX_LENGTH || !ALLOWLIST_ADDRESS.matcher(normalised).matches()) {
                throw new InvalidEmailException(normalised);
            }
            return normalised;
        }

        /** Whether every character is ASCII, so that case folding cannot manufacture a match. */
        static boolean isAscii(String value) {
            for (int i = 0; i < value.length(); i++) {
                if (value.charAt(i) > 0x7F) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * That Google address is already on the allowlist. Checked first for a friendly message; the unique
     * constraint is the real guarantee.
     */
    public static class EmailAlreadyProvisionedException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final String email;

        public EmailAlreadyProvisionedException(String email) {
            super(email + " is already on the allowlist");
            this.email = email;
        }

        public String getEmail() {
            return email;
        }
    }

    /** Not an address the allowlist will accept. A bad address would silently lock that person out. */
    public static class InvalidEmailException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final String email;

        public InvalidEmailException(String email) {
            super("Not a usable Google address: " + email);
            this.email = email;
        }

        public String getEmail() {
            return email;
        }
    }

    /**
     * Refuses a change that would leave no active manager.
     *
     * <p>Why: there are no passwords, so with no manager nobody could get back in without editing the
     * database.
     */
    public static class LastManagerException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public LastManagerException() {
            super("The last active manager cannot be deactivated or demoted");
        }
    }

    /** No user with that id: a stale screen or an edited form post. */
    public static class UnknownUserException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public UnknownUserException(long userId) {
            super("No user with id " + userId);
        }
    }
}
