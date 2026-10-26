package duty.identity.web;

import static org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN;
import duty.identity.application.SignInService.OidcIdentity;
import duty.identity.application.SignInService.SignInOutcome;
import duty.identity.application.SignInService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.StandardClaimNames;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Service;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The security filter chain: Google sign-in only.
 *
 * <p>Everything needs a session except the login page, OAuth endpoints, error page, static assets and
 * the health check. The Apps Script wake endpoint is the one exception; it checks a shared secret
 * instead. {@code @EnableMethodSecurity} turns on the {@code @PreAuthorize} checks on services.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfig {

    /** The Apps Script wake-up endpoint, guarded by its own shared secret. */
    private static final String WAKE_PATH = "/api/ingestion/wake";

    /**
     * Paths reachable without a session.
     *
     * <p>{@code /actuator/health} is here because the hosting platform pings it, and it only answers
     * {@code {"status":"UP"}}.
     */
    private static final String[] PUBLIC_PATHS = {
        "/login", "/error", "/oauth2/**", "/login/oauth2/**",
        "/actuator/health", "/actuator/health/**",
        "/css/**", "/js/**", "/images/**", "/favicon.ico", "/sw.js", "/manifest.webmanifest",
        WAKE_PATH
    };

    private static final String CONTENT_SECURITY_POLICY =
            "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline';"
                    + " frame-ancestors 'none'";

    @Bean
    SecurityFilterChain filterChain(
            HttpSecurity http,
            AllowlistOidcUserService allowlist,
            RoleBasedLanding landing,
            RoleAwareAccessDenied accessDenied)
            throws Exception {
        return http.authorizeHttpRequests(
                        requests -> requests.requestMatchers(PUBLIC_PATHS).permitAll().anyRequest().authenticated())
                // The allowlist is wired in here, so every sign-in passes through it by construction.
                .oauth2Login(
                        login ->
                                login.loginPage("/login")
                                        .failureUrl("/login?error")
                                        // A manager lands on the desktop, an attender on their
                                        // queue: one door, two applications (section 10.4).
                                        .successHandler(landing)
                                        .userInfoEndpoint(userInfo -> userInfo.oidcUserService(allowlist)))
                .logout(logout -> logout.logoutSuccessUrl("/login?logout"))
                // CSRF stays on for every browser form. The wake endpoint is exempt: its caller
                // has no session to protect, and it checks a shared secret instead.
                .exceptionHandling(e -> e.accessDeniedHandler(accessDenied))
                .csrf(csrf -> csrf.ignoringRequestMatchers(WAKE_PATH))
                .headers(
                        headers ->
                                headers.frameOptions(frame -> frame.deny())
                                        .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
                                        .referrerPolicy(referrer -> referrer.policy(SAME_ORIGIN)))
                .build();
    }

    /**
     * Handles a signed-in user reaching a screen their role does not allow.
     *
     * <p>An attender who opens the manager dashboard (from a bookmark, say) is sent to their own queue.
     * Any other refusal stays a 403.
     */
    @Component
    static class RoleAwareAccessDenied implements AccessDeniedHandler {

        private static final String ATTENDER = "ROLE_ATTENDER";

        /** Only the dashboard redirects. Elsewhere a refusal stays a visible 403. */
        private static final java.util.Set<String> LANDING_PATHS = java.util.Set.of("/", "/complaints");

        @Override
        public void handle(
                HttpServletRequest request, HttpServletResponse response, AccessDeniedException denied)
                throws IOException {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            boolean attender =
                    authentication != null
                            && authentication.getAuthorities().stream()
                                    .anyMatch(granted -> ATTENDER.equals(granted.getAuthority()));

            if (attender && "GET".equals(request.getMethod()) && LANDING_PATHS.contains(request.getRequestURI())) {
                response.sendRedirect(request.getContextPath() + "/m");
                return;
            }
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
        }
    }

    /**
     * Where a sign-in lands: the manager's dashboard or the attender's queue.
     *
     * <p>Two handler instances rather than one with a changing target, because the handler is shared by
     * concurrent sign-ins. A saved deep link (e.g. {@code /m/tasks/{id}}) still wins.
     */
    @Component
    static class RoleBasedLanding implements AuthenticationSuccessHandler {

        private static final String ATTENDER_AUTHORITY = "ROLE_ATTENDER";

        private final AuthenticationSuccessHandler toQueue = landingOn("/m");
        private final AuthenticationSuccessHandler toDashboard = landingOn("/");

        @Override
        public void onAuthenticationSuccess(
                HttpServletRequest request, HttpServletResponse response, Authentication authentication)
                throws IOException, ServletException {
            AuthenticationSuccessHandler destination = isAttender(authentication) ? toQueue : toDashboard;
            destination.onAuthenticationSuccess(request, response, authentication);
        }

        private static boolean isAttender(Authentication authentication) {
            return authentication.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .anyMatch(ATTENDER_AUTHORITY::equals);
        }

        private static AuthenticationSuccessHandler landingOn(String url) {
            SavedRequestAwareAuthenticationSuccessHandler handler =
                    new SavedRequestAwareAuthenticationSuccessHandler();
            handler.setDefaultTargetUrl(url);
            return handler;
        }
    }

    /**
     * Where a Google sign-in meets the allowlist.
     *
     * <p>Spring Security has already verified the token; this asks whether the person may use the system,
     * and throws if not. Only the email and its verified flag are read; every other claim is ignored.
     */
    @Service
    static class AllowlistOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

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

    /** The login screen: one "Sign in with Google" button for everyone. No password form exists. */
    @Controller
    static class LoginController {

        /** Spring Security's authorization endpoint for the one registered provider. */
        private static final String GOOGLE_AUTHORIZATION = "/oauth2/authorization/google";

        @GetMapping("/login")
        String login(
                @RequestParam(required = false) String error,
                @RequestParam(required = false) String logout,
                Model model) {
            model.addAttribute("googleAuthorizationUrl", GOOGLE_AUTHORIZATION);
            model.addAttribute("signInFailed", error != null);
            model.addAttribute("signedOut", logout != null);
            return "identity/login";
        }
    }
}
