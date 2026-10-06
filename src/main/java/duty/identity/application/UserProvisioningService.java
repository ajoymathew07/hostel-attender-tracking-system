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
 * Editing the allowlist — the manager's break-glass power, and the reason a forgotten Google
 * account is a support call rather than a rebuild (ADR-002).
 *
 * <p><strong>{@code @PreAuthorize} is on the service, not only on the controller</strong> (section
 * 11.3). Every method is manager-only except {@link #activeAttenderIds()}, which dispatch calls
 * with nobody signed in. The provisioning screen checks too, but a check that lives only in a
 * controller is one refactor away from being bypassed.
 *
 * <p>There is no {@code delete}. A departure or a lost phone is {@link #deactivate(long)}: the row
 * survives, so the audit trail and the point ledger still name a real person years later (section
 * 3.1, principle 4).
 *
 * <p>Every method writes its audit row inside its own transaction (section 11.7), so a rolled-back
 * change leaves no claim that it happened.
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

    /** Everyone on the allowlist, active or not — the provisioning screen's list. */
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
     * The ids of every active attender, lowest first — dispatch's candidate pool (section 6.2).
     *
     * <p>No {@code @PreAuthorize}, deliberately: the caller is the dispatcher, which runs on a
     * scheduled poll with no signed-in user. It returns ids and nothing else — no name, no email,
     * no role — so opening it to the system exposes nothing a manager check was protecting
     * (section 11.3).
     */
    @Transactional(readOnly = true)
    public List<Long> activeAttenderIds() {
        return users.findByRoleAndActiveIsTrueOrderByDisplayNameAsc(UserRole.ATTENDER).stream()
                .map(AppUser::getId)
                .sorted()
                .toList();
    }

    /**
     * The attenders a manager may hand work to, by name.
     *
     * <p>Published here rather than left to the caller to filter, because "who can be assigned"
     * is an identity question — a deactivated account is off the allowlist (section 11.2) and must
     * not appear in the dispatcher's dropdown. It also keeps {@code workflow} from having to know
     * the {@code UserRole} enum, which is this module's internal vocabulary (architecture section
     * 2.4, rule 3).
     */
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('MANAGER')")
    public List<UserSummary> listAssignableAttenders() {
        return users.findByRoleAndActiveIsTrueOrderByDisplayNameAsc(UserRole.ATTENDER).stream()
                .map(UserSummary::of)
                .toList();
    }

    /** Adds a Google address to the allowlist. This is the only way an account comes into being. */
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

    /** Blocks the next sign-in immediately, and keeps the row (section 11.2). */
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

    /** Changes what a user may do. The role is read at each sign-in, so the change takes hold then. */
    @PreAuthorize("hasRole('MANAGER')")
    public UserSummary changeRole(long userId, UserRole role) {
        AppUser user = require(userId);
        requireAnotherManagerRemains(user, role);
        UserRole previous = user.getRole();
        user.changeRole(role);
        return saveAndRecord(
                user, "USER_ROLE_CHANGED", AuditDetail.of("from", previous.name()).and("to", role.name()).json());
    }

    /** Points the allowlist at a different Google account — the account-recovery path of ADR-002. */
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
     * Refuses an operation that would take the last active manager away (ADR-002's break-glass).
     * Deactivation passes {@code ATTENDER} because switching a manager off removes a manager just
     * as demoting one does.
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
