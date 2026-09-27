package in.ac.iitm.hostel.duty.identity.domain;

/**
 * The two roles in the system, matching {@code ck_user_role} (data model section 3.3).
 *
 * <p>There is no third role and no permission table: the authorization matrix is small enough that
 * a role enum plus ownership checks expresses all of it (section 11.3).
 */
public enum UserRole {

    /** Provisions users, triages, assigns, closes, and adjusts the ledger. */
    MANAGER,

    /** Works assigned tasks and earns points. */
    ATTENDER
}
