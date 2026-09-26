package in.ac.iitm.hostel.duty.identity.web;

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
 * login page, the OAuth endpoints Spring Security owns, the error page and static assets. There is
 * no anonymous endpoint: a URL found by a student is a login page and nothing more (section 11.1).
 *
 * <p>{@code @EnableMethodSecurity} is what makes the authorization matrix of section 11.3 real —
 * the roles are enforced on the services, not only here, so a caller reaching a service by another
 * route still meets the check.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfig {

    /** The way in, and the only paths reachable without a session. */
    private static final String[] PUBLIC_PATHS = {
        "/login", "/error", "/oauth2/**", "/login/oauth2/**",
        "/css/**", "/js/**", "/images/**", "/favicon.ico"
    };

    private static final String CONTENT_SECURITY_POLICY =
            "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline';"
                    + " frame-ancestors 'none'";

    @Bean
    SecurityFilterChain filterChain(
            HttpSecurity http, AllowlistOidcUserService allowlist, RoleBasedLanding landing)
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
                // Section 11.6. CSRF protection is Spring Security's default and stays on.
                .headers(
                        headers ->
                                headers.frameOptions(frame -> frame.deny())
                                        .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
                                        .referrerPolicy(referrer -> referrer.policy(SAME_ORIGIN)))
                .build();
    }
}
