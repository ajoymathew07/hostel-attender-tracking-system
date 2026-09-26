package in.ac.iitm.hostel.duty.identity.web;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

/**
 * Where a successful sign-in lands: the manager's desktop, or the attender's phone (section 10.4).
 *
 * <p>There is one login page for everyone (ADR-002) but two applications behind it, so a single
 * {@code defaultSuccessUrl} is wrong for one of the two roles. Before this, every sign-in landed
 * on {@code /}, which is manager-only — an attender's first experience of the system was a 403.
 *
 * <p>Two {@link SavedRequestAwareAuthenticationSuccessHandler} instances rather than one whose
 * target URL is reassigned per request: the handler is a singleton serving concurrent sign-ins,
 * and mutable shared state there is a race that would show up as the wrong landing page for
 * whoever lost it.
 *
 * <p>Saved-request handling is kept, so a deep link into {@code /m/tasks/{id}} from a push
 * notification survives the sign-in it triggers (section 10.2).
 */
@Component
class RoleBasedLanding implements AuthenticationSuccessHandler {

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
