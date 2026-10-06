package duty.identity.application;

import duty.audit.application.AuditDetail;
import duty.audit.application.AuditEntry;
import duty.audit.application.AuditTrail;
import duty.identity.domain.AppUser;
import duty.identity.domain.UserRole;
import duty.identity.infrastructure.AppUserRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
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
}
