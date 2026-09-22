package in.ac.iitm.hostel.duty.identity.domain;

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
 * A principal of the system — the manager, or an attender (data model section 3.3).
 *
 * <p><strong>No credential is stored.</strong> Everyone authenticates with Google, so {@code email}
 * is purely an allowlist key: a sign-in whose verified email has no matching active row is rejected
 * (ADR-002). A database breach therefore yields no credential to steal.
 *
 * <p><strong>There is no points column here.</strong> A balance is a {@code SUM} over the
 * append-only ledger, never stored state — which is precisely why double-crediting cannot happen
 * (section 7.3).
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

    /**
     * Allowlist matching is normalised, so an address differing only in case or surrounding space
     * still admits the same person (test plan E20c).
     */
    public static String normalizeEmail(String email) {
        return Objects.requireNonNull(email, "email").trim().toLowerCase(Locale.ROOT);
    }

    /** Revoking a lost phone: blocks the next sign-in immediately (section 3.3). */
    public void deactivate() {
        this.active = false;
    }

    public void reactivate() {
        this.active = true;
    }

    /** Recovery path when someone loses access to their Google account (section 3.3). */
    public void changeEmail(String newEmail) {
        this.email = normalizeEmail(newEmail);
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
}
