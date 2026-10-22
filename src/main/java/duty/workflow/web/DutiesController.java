package duty.workflow.web;

import duty.identity.application.UserProvisioningService.UserSummary;
import duty.identity.application.UserProvisioningService;
import duty.ledger.application.LeaderboardService;
import duty.ledger.application.PointsLedger;
import duty.workflow.application.SpecialDutyService;
import duty.workflow.application.WorkflowService.GuardFailedException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Points and special duties: the manager's {@code /points} screen and the attender's {@code /m/duties}.
 *
 * <p>The {@code @PreAuthorize} on each manager route is a second layer; the real checks are on the
 * services.
 */
@Controller
class DutiesController {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final PointsLedger ledger;
    private final SpecialDutyService duties;
    private final LeaderboardService leaderboard;
    private final UserProvisioningService users;

    DutiesController(
            PointsLedger ledger,
            SpecialDutyService duties,
            LeaderboardService leaderboard,
            UserProvisioningService users) {
        this.ledger = ledger;
        this.duties = duties;
        this.leaderboard = leaderboard;
        this.users = users;
    }

    // ---- manager ---------------------------------------------------------

    @GetMapping("/points")
    @PreAuthorize("hasRole('MANAGER')")
    String points(Model model) {
        model.addAttribute("attenders", users.listAssignableAttenders());
        model.addAttribute("names", namesById());
        model.addAttribute("leaderboard", leaderboard.currentStandings());
        model.addAttribute("lines", ledger.linesThisMonth());
        model.addAttribute("duties", duties.live());
        // A fresh id per render: submitting the same form twice records it once.
        model.addAttribute("requestId", UUID.randomUUID().toString());
        return "workflow/points";
    }

    @PostMapping("/points/penalty")
    @PreAuthorize("hasRole('MANAGER')")
    String penalise(
            @RequestParam long attenderId,
            @RequestParam(required = false) String note,
            @RequestParam String requestId,
            RedirectAttributes flash) {
        ledger.penalise(attenderId, note, requestId);
        flash.addFlashAttribute("message", "Penalty of " + PointsLedger.PENALTY_POINTS + " logged.");
        return "redirect:/points";
    }

    @PostMapping("/points/adjustment")
    @PreAuthorize("hasRole('MANAGER')")
    String adjust(
            @RequestParam long attenderId,
            @RequestParam int points,
            @RequestParam String note,
            @RequestParam String requestId,
            RedirectAttributes flash) {
        ledger.adjust(attenderId, points, note, requestId);
        flash.addFlashAttribute("message", "Adjustment of " + points + " recorded.");
        return "redirect:/points";
    }

    /** Posts a duty. The closing time is entered in India time. */
    @PostMapping("/duties")
    @PreAuthorize("hasRole('MANAGER')")
    String post(
            @RequestParam String title,
            @RequestParam(required = false) String description,
            @RequestParam int points,
            @RequestParam(defaultValue = "1") int slots,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    LocalDateTime closesAt,
            RedirectAttributes flash) {
        duties.post(
                title, description, points, slots, closesAt == null ? null : closesAt.atZone(IST).toInstant());
        flash.addFlashAttribute("message", "Duty posted. Attenders can claim it now.");
        return "redirect:/points";
    }

    @PostMapping("/duties/{taskId}/cancel")
    @PreAuthorize("hasRole('MANAGER')")
    String cancel(@PathVariable long taskId, RedirectAttributes flash) {
        duties.cancel(taskId);
        flash.addFlashAttribute("message", "Duty cancelled.");
        return "redirect:/points";
    }

    // ---- attender --------------------------------------------------------

    @GetMapping("/m/duties")
    String myDuties(Model model) {
        model.addAttribute("claimable", duties.claimable());
        model.addAttribute("mine", duties.mine());
        model.addAttribute("lines", ledger.myLinesThisMonth());
        model.addAttribute("standing", ledger.myPeriodTotal());
        return "workflow/duties";
    }

    @PostMapping("/m/duties/{taskId}/claim")
    String claim(@PathVariable long taskId, RedirectAttributes flash) {
        duties.claim(taskId);
        flash.addFlashAttribute("message", "Duty claimed. Mark it done when you have finished.");
        return "redirect:/m/duties";
    }

    @PostMapping("/m/duties/{taskId}/done")
    String done(@PathVariable long taskId, RedirectAttributes flash) {
        int points = duties.complete(taskId);
        flash.addFlashAttribute(
                "message", points > 0 ? "Done. +" + points + " point(s)." : "Already marked done.");
        return "redirect:/m/duties";
    }

    /** A refusal becomes a message on the page the request came from. */
    @ExceptionHandler({GuardFailedException.class, IllegalArgumentException.class})
    String refused(RuntimeException refusal, HttpServletRequest request, RedirectAttributes flash) {
        flash.addFlashAttribute("error", refusal.getMessage());
        return request.getRequestURI().startsWith("/m/") ? "redirect:/m/duties" : "redirect:/points";
    }

    /** Everyone's name, so a deactivated attender's past entries still show who they were. */
    private Map<Long, String> namesById() {
        Map<Long, String> names = new LinkedHashMap<>();
        for (UserSummary user : users.listUsers()) {
            names.put(user.id(), user.displayName());
        }
        return names;
    }
}
