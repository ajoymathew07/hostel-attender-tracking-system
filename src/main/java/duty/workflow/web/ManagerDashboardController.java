package duty.workflow.web;

import duty.identity.application.UserProvisioningService;
import duty.identity.application.UserSummary;
import duty.ingestion.application.IngestionHealth;
import duty.ledger.application.LeaderboardService;
import duty.workflow.application.ComplaintQueryService;
import duty.workflow.application.GuardFailedException;
import duty.workflow.application.IllegalTransitionException;
import duty.workflow.application.ManualAssignmentService;
import duty.workflow.application.PriorityService;
import duty.workflow.application.TransitionPayload;
import duty.workflow.application.TriageService;
import duty.workflow.application.WorkflowService;
import duty.workflow.domain.ComplaintDomain;
import duty.workflow.domain.WorkflowEvent;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * The manager's desktop: {@code /} and {@code /complaints} (section 10.4).
 *
 * <p>The complaint list, manual assignment, the triage panel and the leaderboard are all of it for
 * now. The live activity feed and the analytics strip of section 10.3 each belong to the module
 * that owns them and arrive with it; a dashboard full of empty placeholder panels would be worse
 * than a short one that is entirely real.
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

    /** The four buttons on a triage card: every domain a manager may actually pick. */
    private static final List<ComplaintDomain> ASSIGNABLE_DOMAINS =
            Arrays.stream(ComplaintDomain.values()).filter(ComplaintDomain::isDispatchable).toList();

    private final ComplaintQueryService complaints;
    private final ManualAssignmentService assignment;
    private final UserProvisioningService users;
    private final LeaderboardService leaderboard;
    private final WorkflowService workflow;
    private final IngestionHealth ingestion;
    private final TriageService triage;
    private final PriorityService priorities;

    ManagerDashboardController(
            ComplaintQueryService complaints,
            ManualAssignmentService assignment,
            UserProvisioningService users,
            LeaderboardService leaderboard,
            WorkflowService workflow,
            IngestionHealth ingestion,
            TriageService triage,
            PriorityService priorities) {
        this.complaints = complaints;
        this.assignment = assignment;
        this.users = users;
        this.leaderboard = leaderboard;
        this.workflow = workflow;
        this.ingestion = ingestion;
        this.triage = triage;
        this.priorities = priorities;
    }

    @GetMapping({"/", "/complaints"})
    String list(Model model) {
        // Above everything, and counted now rather than read from a stored total (section 10.3).
        model.addAttribute("counters", complaints.counters());
        model.addAttribute("complaints", complaints.listAll());
        // Above the list in the template, not in a tab: it is the only part of this screen that
        // requires the manager's action, and anything requiring action goes where the eye lands
        // first (section 10.3).
        model.addAttribute("triage", complaints.listTriage());
        model.addAttribute("assignableDomains", ASSIGNABLE_DOMAINS);
        model.addAttribute("domainGlyphs", DomainGlyphs.BY_DOMAIN);
        // Only active attenders can be given work; the name map covers everyone, so a complaint
        // held by someone since deactivated still shows who holds it rather than a bare id.
        model.addAttribute("attenders", users.listAssignableAttenders());
        model.addAttribute("attenderNames", namesById(users.listUsers()));
        // A derived projection, recomputed on every render. There is no cached total to go stale
        // and no counter to reconcile (section 7.8).
        model.addAttribute("leaderboard", leaderboard.currentStandings());
        // Section 5.3's degraded-ingestion banner. Today a failed run is only visible to someone
        // who goes looking at run history, and the manager's whole workflow assumes complaints
        // are arriving — so the screen they are already on has to say when they are not.
        model.addAttribute("ingestionDegraded", ingestion.degraded());
        model.addAttribute("ingestionFailures", ingestion.consecutiveFailures());
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

    /**
     * The triage card's one-click correction (sections 5.5, 10.3).
     *
     * <p>Four buttons in one form, each submitting its own {@code domain} value, so the gesture is
     * a single press rather than pick-then-confirm. The complaint stays in {@code TRIAGE} until
     * somebody is assigned to it, which is the control beside these.
     */
    @PostMapping("/complaints/{complaintId}/domain")
    String setDomain(
            @PathVariable long complaintId,
            @RequestParam ComplaintDomain domain,
            RedirectAttributes flash) {
        triage.classify(complaintId, domain);
        flash.addFlashAttribute("message", "Domain set to " + domain + ".");
        return REDIRECT;
    }

    /**
     * Section 4.5's third priority row: the manager judges this less urgent than it arrived.
     *
     * <p>One button and no priority picker, because P3 is the only priority a manager sets. P1 is
     * the safety keywords' and P2 is the default; letting the screen choose either would let a
     * judgement about urgency overwrite a fact about a hazard. Aging can raise a P3 again, and the
     * list will then say "aged from P3".
     */
    @PostMapping("/complaints/{complaintId}/downgrade")
    String downgrade(@PathVariable long complaintId, RedirectAttributes flash) {
        priorities.downgrade(complaintId);
        flash.addFlashAttribute("message", "Downgraded to P3, and the due date recomputed.");
        return REDIRECT;
    }

    @PostMapping("/complaints/{complaintId}/release")
    String release(@PathVariable long complaintId, RedirectAttributes flash) {
        assignment.release(complaintId);
        flash.addFlashAttribute("message", "Released back to the pool.");
        return REDIRECT;
    }

    /** Transition T15 — the manager signs off a resolved complaint. */
    @PostMapping("/complaints/{complaintId}/close")
    String close(@PathVariable long complaintId, RedirectAttributes flash) {
        workflow.transition(complaintId, WorkflowEvent.MANAGER_CLOSE, TransitionPayload.none());
        flash.addFlashAttribute("message", "Closed.");
        return REDIRECT;
    }

    /**
     * Transition T17 — it was not fixed after all.
     *
     * <p>The credit is reversed by a new ledger row, never a delete, so the attender's history
     * shows both the +2 and the −2 and the leaderboard nets out correctly (section 7.3).
     */
    @PostMapping("/complaints/{complaintId}/reopen")
    String reopen(
            @PathVariable long complaintId, @RequestParam String reason, RedirectAttributes flash) {
        workflow.transition(complaintId, WorkflowEvent.MANAGER_REOPEN, TransitionPayload.reason(reason));
        flash.addFlashAttribute("message", "Reopened, and the points credited for it reversed.");
        return REDIRECT;
    }

    /** A refused move is a sentence on the list, not a stack trace (section 11.6). */
    @ExceptionHandler({IllegalTransitionException.class, GuardFailedException.class})
    String refused(RuntimeException refusal, RedirectAttributes flash) {
        flash.addFlashAttribute("error", refusal.getMessage());
        return REDIRECT;
    }

    private static Map<Long, String> namesById(List<UserSummary> everyone) {
        Map<Long, String> names = new LinkedHashMap<>();
        everyone.forEach(user -> names.put(user.id(), user.displayName()));
        return names;
    }
}
