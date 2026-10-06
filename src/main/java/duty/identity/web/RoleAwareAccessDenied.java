package duty.identity.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * What a signed-in user sees when they reach a screen their role does not own.
 *
 * <p>The common case is not an attack: it is an attender opening the manager dashboard from a
 * bookmark, the back button, or a link someone sent them. {@link RoleBasedLanding} already sends
 * them to their queue at sign-in, but that only fires once, so without this they meet a raw error
 * page on a screen they were never meant to reach. Sending them where they *can* work is the honest
 * answer to "you are in the wrong place".
 *
 * <p>Anything else is a genuine refusal and keeps its 403 (section 11.3).
 */
@Component
class RoleAwareAccessDenied implements AccessDeniedHandler {

    private static final String ATTENDER = "ROLE_ATTENDER";

    /**
     * Only the dashboard itself. A refusal anywhere else — the provisioning screen, any POST at a
     * manager endpoint — is a genuine refusal and stays a 403, because turning those into a
     * redirect would hide an attempt that ought to be visible.
     */
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
