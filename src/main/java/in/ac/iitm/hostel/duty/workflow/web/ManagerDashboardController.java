package in.ac.iitm.hostel.duty.workflow.web;

import in.ac.iitm.hostel.duty.identity.application.UserProvisioningService;
import in.ac.iitm.hostel.duty.identity.application.UserSummary;
import in.ac.iitm.hostel.duty.ledger.application.LeaderboardService;
import in.ac.iitm.hostel.duty.workflow.application.ComplaintQueryService;
import in.ac.iitm.hostel.duty.workflow.application.ManualAssignmentService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The manager's desktop: {@code /} and {@code /complaints} (section 10.4).
 *
 * <p>The complaint list, manual assignment and the leaderboard are all of it for now. Triage, the
 * live activity feed and the analytics strip of section 10.3 each belong to the module that owns
 * them and arrive with it; a dashboard full of empty placeholder panels would be worse than a
 * short one that is entirely real.
 *
 * <p>The class-level {@code @PreAuthorize} is defence in depth. The same rules are on {@code
 * ComplaintQueryService} and {@link ManualAssignmentService}, which is where section 11.3 requires
 * them: remove this one and the screen is still manager-only.
 */
@Controller
@PreAuthorize("hasRole('MANAGER')")
class ManagerDashboardController {

    private static final String VIEW = "workflow/complaints";
    private static final String REDIRECT = "redirect:/complaints";

    private final ComplaintQueryService complaints;
    private final ManualAssignmentService assignment;
    private final UserProvisioningService users;
    private final LeaderboardService leaderboard;

    ManagerDashboardController(
            ComplaintQueryService complaints,
            ManualAssignmentService assignment,
            UserProvisioningService users,
            LeaderboardService leaderboard) {
        this.complaints = complaints;
        this.assignment = assignment;
        this.users = users;
        this.leaderboard = leaderboard;
    }

    @GetMapping({"/", "/complaints"})
    String list(Model model) {
        model.addAttribute("complaints", complaints.listAll());
        model.addAttribute("domainGlyphs", DomainGlyphs.BY_DOMAIN);
        // Only active attenders can be given work; the name map covers everyone, so a complaint
        // held by someone since deactivated still shows who holds it rather than a bare id.
        model.addAttribute("attenders", users.listAssignableAttenders());
        model.addAttribute("attenderNames", namesById(users.listUsers()));
        // A derived projection, recomputed on every render. There is no cached total to go stale
        // and no counter to reconcile (section 7.8).
        model.addAttribute("leaderboard", leaderboard.currentStandings());
        return VIEW;
    }

    /** Assign, or reassign — one screen control, so one route (section 10.3). */
    @PostMapping("/complaints/{complaintId}/assign")
    String assign(
            @PathVariable long complaintId,
            @RequestParam long attenderId,
            RedirectAttributes flash) {
        assignment.assign(complaintId, attenderId);
        flash.addFlashAttribute("message", "Assignment updated.");
        return REDIRECT;
    }

    @PostMapping("/complaints/{complaintId}/release")
    String release(@PathVariable long complaintId, RedirectAttributes flash) {
        assignment.release(complaintId);
        flash.addFlashAttribute("message", "Released back to the pool.");
        return REDIRECT;
    }

    private static Map<Long, String> namesById(List<UserSummary> everyone) {
        Map<Long, String> names = new LinkedHashMap<>();
        everyone.forEach(user -> names.put(user.id(), user.displayName()));
        return names;
    }
}
