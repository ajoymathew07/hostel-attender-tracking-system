package duty.identity.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The login screen (section 10.4): a single "Sign in with Google" button, for everyone.
 *
 * <p>There is no form to post to and no controller action behind the button — it is a link to
 * Spring Security's authorization endpoint. That absence is the design: no password field means no
 * password reset, no rate limiter and no credential to leak (ADR-002).
 */
@Controller
class LoginController {

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
