package duty.identity.web;

import static org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * One filter chain, because there is one way in (ADR-002).
 *
 * <p>An earlier draft of the design had two — a form login for the manager and OAuth for everyone
 * else. Collapsing to Google-only deleted the second branch, and the password encoder and login
 * controller with it; what is left is short enough to read in one sitting, which is the point.
 *
 * <p><strong>Everything is authenticated except the way in.</strong> The permitted list is the
 * login page, the OAuth endpoints Spring Security owns, the error page and static assets. A URL
 * found by a student is a login page and nothing more (section 11.1).
 *
 * <p>There is exactly one exception, and it is not anonymous in any useful sense: the Apps Script
 * wake-up trigger of section 2.5. Its caller is a Google server, so it can hold no session and
 * carry no CSRF token; it proves itself with a shared secret the controller compares in constant
 * time (section 11.6). It is listed here by path because a controller in another module is
 * invisible to this one, and it is the only path on both lists below.
 *
 * <p>{@code @EnableMethodSecurity} is what makes the authorization matrix of section 11.3 real —
 * the roles are enforced on the services, not only here, so a caller reaching a service by another
 * route still meets the check.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfig {

    /** The Apps Script wake-up trigger (section 2.5); guarded by its own shared secret. */
    private static final String WAKE_PATH = "/api/ingestion/wake";

    /**
     * The way in, and the only paths reachable without a session.
     *
     * <p>{@code /actuator/health} is here because the hosting platform pings it to decide whether a
     * deploy is live, and it has no session to present. It is safe to open because it is the only
     * actuator endpoint {@code management.endpoints.web.exposure.include} maps at all, and it
     * answers with a bare {@code {"status":"UP"}} — no components, no build, no environment.
     *
     * <p>{@link #WAKE_PATH} is the other exception, and it is not anonymous in any useful sense:
     * its caller is a Google server, so it can hold no session, and it proves itself with a shared
     * secret the controller compares in constant time (section 11.6).
     */
    private static final String[] PUBLIC_PATHS = {
        "/login", "/error", "/oauth2/**", "/login/oauth2/**",
        "/actuator/health", "/actuator/health/**",
        "/css/**", "/js/**", "/images/**", "/favicon.ico",
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
                // Section 11.6. CSRF protection is Spring Security's default and stays on for
                // every browser form. The wake-up trigger is exempt because its caller is not a
                // browser and has no session to ride: CSRF defends a session, and there is none
                // here to defend. The shared secret is what stands in its place.
                // An attender who reaches a manager screen is sent to their queue rather
                // than shown an error page; everything else keeps its 403.
                .exceptionHandling(e -> e.accessDeniedHandler(accessDenied))
                .csrf(csrf -> csrf.ignoringRequestMatchers(WAKE_PATH))
                .headers(
                        headers ->
                                headers.frameOptions(frame -> frame.deny())
                                        .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
                                        .referrerPolicy(referrer -> referrer.policy(SAME_ORIGIN)))
                .build();
    }
}
