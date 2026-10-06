package duty.identity.domain;

/** The two roles. Matches the {@code ck_user_role} constraint. */
public enum UserRole {

    /** Provisions users, triages, assigns, closes, and adjusts the ledger. */
    MANAGER,

    /** Works assigned tasks and earns points. */
    ATTENDER
}
