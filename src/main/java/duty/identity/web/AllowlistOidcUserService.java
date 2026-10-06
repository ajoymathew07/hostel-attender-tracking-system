package duty.identity.web;

import duty.identity.application.OidcIdentity;
import duty.identity.application.SignInOutcome;
import duty.identity.application.SignInService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.StandardClaimNames;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

/**
 * Where a Google sign-in meets the allowlist.
 *
 * <p>Spring Security has already verified the token; this asks whether the person may use the system,
 * and throws if not. Only the email and its verified flag are read; every other claim is ignored.
 */
@Service
class AllowlistOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

    /** One error code for every refusal, whatever the reason. */
    private static final String NOT_PROVISIONED = "NOT_PROVISIONED";

    private static final String REFUSAL_MESSAGE =
            "This Google account is not permitted to use the hostel duty tracker."
                    + " Ask the hostel manager to add it.";

    private final SignInService signIn;

    /** Fetches the user info; stubbed in tests. */
    private final OAuth2UserService<OidcUserRequest, OidcUser> delegate;

    @Autowired
    AllowlistOidcUserService(SignInService signIn) {
        this(signIn, new OidcUserService());
    }

    AllowlistOidcUserService(SignInService signIn, OAuth2UserService<OidcUserRequest, OidcUser> delegate) {
        this.signIn = signIn;
        this.delegate = delegate;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest userRequest) {
        OidcUser fromGoogle = delegate.loadUser(userRequest);

        SignInOutcome outcome =
                signIn.admit(
                        new OidcIdentity(
                                fromGoogle.getEmail(),
                                isVerified(fromGoogle.getClaims().get(StandardClaimNames.EMAIL_VERIFIED))));

        if (!outcome.admitted()) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error(NOT_PROVISIONED, REFUSAL_MESSAGE, null), REFUSAL_MESSAGE);
        }

        return outcome.user().withOidcTokens(userRequest.getIdToken(), fromGoogle.getUserInfo());
    }

    /** Missing means unverified. Some providers send a string, so it is parsed rather than cast. */
    private static boolean isVerified(Object claim) {
        if (claim instanceof Boolean verified) {
            return verified;
        }
        return claim != null && Boolean.parseBoolean(claim.toString());
    }
}
