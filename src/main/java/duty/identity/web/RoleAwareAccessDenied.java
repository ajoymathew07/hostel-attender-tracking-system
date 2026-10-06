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
 * Handles a signed-in user reaching a screen their role does not allow.
 *
 * <p>An attender who opens the manager dashboard (from a bookmark, say) is sent to their own queue.
 * Any other refusal stays a 403.
 */
@Component
class RoleAwareAccessDenied implements AccessDeniedHandler {

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
