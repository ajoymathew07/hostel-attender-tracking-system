package duty.identity.application;

import duty.identity.domain.UserRole;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * The authenticated principal: one {@code app_user} row that has just been admitted by the
 * allowlist.
 *
 * <p><strong>The authority comes from {@code app_user.role}, never from a Google claim</strong>
 * (section 11.3). That is the whole point of this class existing rather than Spring's {@code
 * DefaultOidcUser}: the default builds its authorities from the token's scopes, which would mean an
 * identity provider outside our control had a say in what a user may do here.
 *
 * <p>It lives in {@code application} because it is the identity module's published answer to "who
 * is signed in" — other modules read it through {@link CurrentUser} and must never reach into
 * {@code identity.domain} for an {@code AppUser} (architecture section 2.4, rule 3). It carries the
 * role enum rather than a whole entity for the same reason: a detached entity in an HTTP session is
 * a stale copy waiting to be trusted.
 */
public final class SignedInUser implements OidcUser, Serializable {

    private static final long serialVersionUID = 1L;

    private final long userId;
    private final String email;
    private final String displayName;
    private final UserRole role;

    /** Present only inside the OIDC flow; absent for a principal built in a test or a job. */
    private final OidcIdToken idToken;

    private final OidcUserInfo userInfo;

    public SignedInUser(long userId, String email, String displayName, UserRole role) {
        this(userId, email, displayName, role, null, null);
    }

    public SignedInUser(
            long userId,
            String email,
            String displayName,
            UserRole role,
            OidcIdToken idToken,
            OidcUserInfo userInfo) {
        this.userId = userId;
        this.email = Objects.requireNonNull(email, "email");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.role = Objects.requireNonNull(role, "role");
        this.idToken = idToken;
        this.userInfo = userInfo;
    }

    /** The same principal, carrying the tokens of the flow that produced it. */
    public SignedInUser withOidcTokens(OidcIdToken token, OidcUserInfo info) {
        return new SignedInUser(userId, email, displayName, role, token, info);
    }

    public long getUserId() {
        return userId;
    }

    public String getEmail() {
        return email;
    }

    public String getDisplayName() {
        return displayName;
    }

    public UserRole getRole() {
        return role;
    }

    /** {@code ROLE_MANAGER} or {@code ROLE_ATTENDER}, derived from the allowlist row alone. */
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public Map<String, Object> getClaims() {
        return idToken == null ? Map.of() : idToken.getClaims();
    }

    @Override
    public Map<String, Object> getAttributes() {
        return getClaims();
    }

    @Override
    public OidcUserInfo getUserInfo() {
        return userInfo;
    }

    @Override
    public OidcIdToken getIdToken() {
        return idToken;
    }

    /** The email, because it is the allowlist key and the one stable handle on this person. */
    @Override
    public String getName() {
        return email;
    }

    @Override
    public String toString() {
        return "SignedInUser[" + userId + ", " + email + ", " + role + "]";
    }
}
