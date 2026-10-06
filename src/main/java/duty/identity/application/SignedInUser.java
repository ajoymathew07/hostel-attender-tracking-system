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
 * The signed-in user: one {@code app_user} row admitted by the allowlist.
 *
 * <p>Why not Spring's {@code DefaultOidcUser}: that takes authorities from Google's token, whereas the
 * role here comes only from {@code app_user.role}. Other modules read it through {@link CurrentUser}.
 */
public final class SignedInUser implements OidcUser, Serializable {

    private static final long serialVersionUID = 1L;

    private final long userId;
    private final String email;
    private final String displayName;
    private final UserRole role;

    /** Only set inside the OIDC login flow. */
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

    /** {@code ROLE_MANAGER} or {@code ROLE_ATTENDER}, from the allowlist row. */
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

    /** The email: the allowlist key. */
    @Override
    public String getName() {
        return email;
    }

    @Override
    public String toString() {
        return "SignedInUser[" + userId + ", " + email + ", " + role + "]";
    }
}
