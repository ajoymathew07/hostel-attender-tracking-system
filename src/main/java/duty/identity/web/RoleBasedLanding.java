package duty.identity.web;

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
 * Where a sign-in lands: the manager's dashboard or the attender's queue.
 *
 * <p>Two handler instances rather than one with a changing target, because the handler is shared by
 * concurrent sign-ins. A saved deep link (e.g. {@code /m/tasks/{id}}) still wins.
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
