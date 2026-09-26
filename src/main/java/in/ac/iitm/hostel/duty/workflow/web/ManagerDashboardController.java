package in.ac.iitm.hostel.duty.workflow.web;

import in.ac.iitm.hostel.duty.workflow.application.ComplaintQueryService;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The manager's desktop: {@code /} and {@code /complaints} (section 10.4).
 *
 * <p>This is the route PR 3 deliberately left unbuilt — a successful sign-in had nowhere to land.
 * Section 10.4 puts the manager dashboard at {@code /}, so that is what it is.
 *
 * <p>The complaint list is all of it for now. Triage, the live activity feed, the leaderboard and
 * the analytics strip of section 10.3 each belong to the module that owns them and arrive with it;
 * a dashboard full of empty placeholder panels would be worse than a short one that is entirely
 * real.
 *
 * <p>The class-level {@code @PreAuthorize} is defence in depth. The same rule is on {@code
 * ComplaintQueryService}'s implementation, which is where section 11.3 requires it: remove this
 * one and the screen is still manager-only.
 */
@Controller
@PreAuthorize("hasRole('MANAGER')")
class ManagerDashboardController {

    private static final String VIEW = "workflow/complaints";

    /**
     * Decorative glyphs for the domain badge (section 10.5).
     *
     * <p>Colour is never the only signal, so each badge carries an icon and a text label as well
     * as a hue — which also covers colour-blind users. The glyph is {@code aria-hidden} in the
     * template, so a screen reader reads the label alone rather than announcing an emoji's name.
     */
    private static final Map<String, String> DOMAIN_GLYPHS =
            Map.of(
                    "ELECTRICAL", "⚡",
                    "CIVIL", "🔧",
                    "RO_PLANT", "💧",
                    "NETWORK", "🌐",
                    "UNCLASSIFIED", "❓");

    private final ComplaintQueryService complaints;

    ManagerDashboardController(ComplaintQueryService complaints) {
        this.complaints = complaints;
    }

    @GetMapping({"/", "/complaints"})
    String list(Model model) {
        model.addAttribute("complaints", complaints.listAll());
        model.addAttribute("domainGlyphs", DOMAIN_GLYPHS);
        return VIEW;
    }
}
