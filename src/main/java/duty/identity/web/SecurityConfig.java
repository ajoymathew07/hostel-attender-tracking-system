package duty.identity.web;

import static org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

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
}
