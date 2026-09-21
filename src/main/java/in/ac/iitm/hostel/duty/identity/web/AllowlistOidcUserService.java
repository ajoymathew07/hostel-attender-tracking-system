package in.ac.iitm.hostel.duty.identity.web;

import in.ac.iitm.hostel.duty.identity.application.OidcIdentity;
import in.ac.iitm.hostel.duty.identity.application.SignInOutcome;
import in.ac.iitm.hostel.duty.identity.application.SignInService;
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
 * Where a Google sign-in meets the allowlist (section 11.2).
 *
 * <p>Spring Security has by this point verified the id token and completed the authorization-code
 * flow — that is its code, and it is not re-tested (ADR-002). This class then asks the one question
 * Google cannot answer: <em>may this person use this system?</em> If the answer is no, it throws,
 * and the flow ends with no session and no row created.
 *
 * <p>Exactly two claims are read, and only one of them is data: the address, and whether Google
 * says it verified it. Everything else in the token — the profile name, the hosted domain, any
 * authority the provider fancies asserting — is discarded here rather than downstream, so there is
 * no later code that could be tempted to trust it.
 */
@Service
class AllowlistOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

    /** Section 9.7: one code for every refusal, whatever the underlying reason. */
    private static final String NOT_PROVISIONED = "NOT_PROVISIONED";

    private static final String REFUSAL_MESSAGE =
            "This Google account is not permitted to use the hostel duty tracker."
                    + " Ask the hostel manager to add it.";

    private final SignInService signIn;

    /** Fetches the user info; replaced by a stub in tests so no network is involved. */
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

    /**
     * Absent means unverified. Some providers send the claim as a string, so it is parsed rather
     * than cast — a {@code ClassCastException} here would become a 500, and a 500 on the login path
     * is the kind of failure people work around by loosening the check.
     */
    private static boolean isVerified(Object claim) {
        if (claim instanceof Boolean verified) {
            return verified;
        }
        return claim != null && Boolean.parseBoolean(claim.toString());
    }
}
