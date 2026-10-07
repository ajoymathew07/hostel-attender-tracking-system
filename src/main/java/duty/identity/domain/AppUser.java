package duty.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/**
 * A user: the manager or an attender.
 *
 * <p>No credential is stored; everyone signs in with Google and {@code email} is only the allowlist
 * key. There is no points column: points are summed from the ledger.
 */
@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 16)
    private UserRole role;

    @Column(name = "email", nullable = false, length = 255)
    private String email;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** The manager who provisioned this row; {@code null} for the two seeded managers. */
    @Column(name = "created_by")
    private Long createdBy;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /** For JPA only. */
    protected AppUser() {}

    public AppUser(String displayName, UserRole role, String email, Instant createdAt, Long createdBy) {
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.role = Objects.requireNonNull(role, "role");
        this.email = normalizeEmail(email);
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.createdBy = createdBy;
        this.active = true;
    }

    /** Trimmed and lower-cased, so case and spacing differences still match. */
    public static String normalizeEmail(String email) {
        return Objects.requireNonNull(email, "email").trim().toLowerCase(Locale.ROOT);
    }

    /** Blocks the next sign-in immediately. */
    public void deactivate() {
        this.active = false;
    }

    public void reactivate() {
        this.active = true;
    }

    /** Recovery path when someone loses access to their Google account. */
    public void changeEmail(String newEmail) {
        this.email = normalizeEmail(newEmail);
    }

    /** Changes the role; it is read at each sign-in, so it takes effect then. */
    public void changeRole(UserRole newRole) {
        this.role = Objects.requireNonNull(newRole, "role");
    }

    public void rename(String newDisplayName) {
        this.displayName = Objects.requireNonNull(newDisplayName, "displayName");
    }

    public void recordLogin(Instant at) {
        this.lastLoginAt = Objects.requireNonNull(at, "at");
    }

    public Long getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public UserRole getRole() {
        return role;
    }

    public String getEmail() {
        return email;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public long getVersion() {
        return version;
    }

    /** The two roles. Matches the {@code ck_user_role} constraint. */
    public enum UserRole {

        /** Provisions users, triages, assigns, closes, and adjusts the ledger. */
        MANAGER,

        /** Works assigned tasks and earns points. */
        ATTENDER
    }
}
