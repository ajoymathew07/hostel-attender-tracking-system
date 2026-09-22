package in.ac.iitm.hostel.duty.identity.application;

import in.ac.iitm.hostel.duty.audit.application.AuditDetail;
import in.ac.iitm.hostel.duty.audit.application.AuditEntry;
import in.ac.iitm.hostel.duty.audit.application.AuditTrail;
import in.ac.iitm.hostel.duty.identity.domain.AppUser;
import in.ac.iitm.hostel.duty.identity.domain.UserRole;
import in.ac.iitm.hostel.duty.identity.infrastructure.AppUserRepository;
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
 * 11.3). The provisioning screen checks too, but a check that lives only in a controller is one
 * refactor away from being bypassed.
 *
 * <p>Every method writes its audit row inside its own transaction (section 11.7), so a rolled-back
 * change leaves no claim that it happened.
 */
@Service
@Transactional
class DefaultUserProvisioningService implements UserProvisioningService {

    private static final String ENTITY_TYPE = "app_user";

    private final AppUserRepository users;
    private final AuditTrail audit;

    DefaultUserProvisioningService(AppUserRepository users, AuditTrail audit) {
        this.users = users;
        this.audit = audit;
    }

    @Override
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

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('MANAGER')")
    public List<UserSummary> listAssignableAttenders() {
        return users.findByRoleAndActiveIsTrueOrderByDisplayNameAsc(UserRole.ATTENDER).stream()
                .map(UserSummary::of)
                .toList();
    }

    @Override
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

    @Override
    @PreAuthorize("hasRole('MANAGER')")
    public UserSummary deactivate(long userId) {
        AppUser user = require(userId);
        requireAnotherManagerRemains(user, UserRole.ATTENDER);
        user.deactivate();
        return saveAndRecord(user, "USER_DEACTIVATED", AuditDetail.of("active", false).json());
    }

    @Override
    @PreAuthorize("hasRole('MANAGER')")
    public UserSummary reactivate(long userId) {
        AppUser user = require(userId);
        user.reactivate();
        return saveAndRecord(user, "USER_REACTIVATED", AuditDetail.of("active", true).json());
    }

    @Override
    @PreAuthorize("hasRole('MANAGER')")
    public UserSummary changeRole(long userId, UserRole role) {
        AppUser user = require(userId);
        requireAnotherManagerRemains(user, role);
        UserRole previous = user.getRole();
        user.changeRole(role);
        return saveAndRecord(
                user, "USER_ROLE_CHANGED", AuditDetail.of("from", previous.name()).and("to", role.name()).json());
    }

    @Override
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
