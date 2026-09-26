package in.ac.iitm.hostel.duty.identity.application;

import in.ac.iitm.hostel.duty.identity.domain.UserRole;
import java.util.List;

/**
 * The identity module's published provisioning surface (architecture section 2.4, rule 3).
 *
 * <p>Every method here is manager-only (section 11.3), enforced with {@code @PreAuthorize} on the
 * implementation — at the service layer, so a second caller cannot reach it by a route that skips a
 * controller's check.
 *
 * <p>There is no {@code delete}. A departure or a lost phone is {@link #deactivate(long)}: the row
 * survives, so the audit trail and the point ledger still name a real person years later (section
 * 3.1, principle 4).
 */
public interface UserProvisioningService {

    /** Everyone on the allowlist, active or not — the provisioning screen's list. */
    List<UserSummary> listUsers();

    /**
     * The attenders a manager may hand work to, by name.
     *
     * <p>Published here rather than left to the caller to filter, because "who can be assigned"
     * is an identity question — a deactivated account is off the allowlist (section 11.2) and must
     * not appear in the dispatcher's dropdown. It also keeps {@code workflow} from having to know
     * the {@code UserRole} enum, which is this module's internal vocabulary (architecture section
     * 2.4, rule 3).
     */
    List<UserSummary> listAssignableAttenders();

    /** Adds a Google address to the allowlist. This is the only way an account comes into being. */
    UserSummary provision(String displayName, String email, UserRole role);

    /** Blocks the next sign-in immediately, and keeps the row (section 11.2). */
    UserSummary deactivate(long userId);

    /** Puts a deactivated user back on the allowlist. */
    UserSummary reactivate(long userId);

    /** Changes what a user may do. The role is read at each sign-in, so the change takes hold then. */
    UserSummary changeRole(long userId, UserRole role);

    /** Points the allowlist at a different Google account — the account-recovery path of ADR-002. */
    UserSummary changeEmail(long userId, String email);
}
