package duty.workflow.web;

import duty.identity.application.UserProvisioningService.UserSummary;
import duty.identity.application.UserProvisioningService;
import duty.ingestion.application.SheetsIngestionService.IngestionHealth;
import duty.ledger.application.LeaderboardService;
import duty.workflow.application.ComplaintQueryService;
import duty.workflow.application.ManualAssignmentService;
import duty.workflow.application.PriorityService;
import duty.workflow.application.TaskResolutionService;
import duty.workflow.application.TriageService;
import duty.workflow.application.WorkflowService.GuardFailedException;
import duty.workflow.application.WorkflowService.IllegalTransitionException;
import duty.workflow.application.WorkflowService.TransitionPayload;
import duty.workflow.application.WorkflowService;
import duty.workflow.domain.Complaint.ComplaintDomain;
import duty.workflow.domain.ComplaintTransitions.WorkflowEvent;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The manager's dashboard: {@code /} and {@code /complaints}. Complaint list, assignment, triage and
 * leaderboard.
 *
 * <p>The {@code @PreAuthorize} here is a second layer; the real checks are on the services.
 */
@Controller
@PreAuthorize("hasRole('MANAGER')")
class ManagerDashboardController {

    private static final String VIEW = "workflow/complaints";
    private static final String REDIRECT = "redirect:/complaints";

    /** The domains a manager can pick on a triage card. */
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
    private final TaskResolutionService resolution;

    ManagerDashboardController(
            ComplaintQueryService complaints,
            ManualAssignmentService assignment,
            UserProvisioningService users,
            LeaderboardService leaderboard,
            WorkflowService workflow,
            IngestionHealth ingestion,
            TriageService triage,
            PriorityService priorities,
            TaskResolutionService resolution) {
        this.complaints = complaints;
        this.assignment = assignment;
        this.users = users;
        this.leaderboard = leaderboard;
        this.workflow = workflow;
        this.ingestion = ingestion;
        this.triage = triage;
        this.priorities = priorities;
        this.resolution = resolution;
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

    /** One complaint's history, with the act-as controls for whoever holds it. */
    @GetMapping("/complaints/{complaintId}")
    String history(@PathVariable long complaintId, Model model) {
        model.addAttribute(
                "history",
                complaints
                        .history(complaintId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
        model.addAttribute("names", namesById(users.listUsers()));
        model.addAttribute("steps", TaskResolutionService.ATTENDER_STEPS);
        model.addAttribute("domainGlyphs", DomainGlyphs.BY_DOMAIN);
        return "workflow/complaint";
    }

    /**
     * Act-as: the manager takes the holder's step during roll-call. A refusal is shown on the same page
     * rather than the list.
     */
    @PostMapping("/complaints/{complaintId}/act-as")
    String actAs(
            @PathVariable long complaintId,
            @RequestParam WorkflowEvent step,
            @RequestParam(required = false) String value,
            RedirectAttributes flash) {
        try {
            resolution.actAs(complaintId, step, value);
            flash.addFlashAttribute("message", "Done for the attender: " + step + ".");
        } catch (IllegalTransitionException | GuardFailedException refusal) {
            flash.addFlashAttribute("error", refusal.getMessage());
        }
        return "redirect:/complaints/" + complaintId;
    }

    /** Assign or reassign. */
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
     * Sets the domain from a triage card, in one click. The complaint stays in triage until assigned.
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
     * Downgrades to P3, the only priority a manager sets. P1 comes from safety keywords and P2 is the
     * default.
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

    /** Closes a resolved complaint (T15). */
    @PostMapping("/complaints/{complaintId}/close")
    String close(@PathVariable long complaintId, RedirectAttributes flash) {
        workflow.transition(complaintId, WorkflowEvent.MANAGER_CLOSE, TransitionPayload.none());
        flash.addFlashAttribute("message", "Closed.");
        return REDIRECT;
    }

    /** Reopens a complaint (T17). The points are reversed with a new ledger row, not deleted. */
    @PostMapping("/complaints/{complaintId}/reopen")
    String reopen(
            @PathVariable long complaintId, @RequestParam String reason, RedirectAttributes flash) {
        workflow.transition(complaintId, WorkflowEvent.MANAGER_REOPEN, TransitionPayload.reason(reason));
        flash.addFlashAttribute("message", "Reopened, and the points credited for it reversed.");
        return REDIRECT;
    }

    /** A refused move becomes a message on the list. */
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

    /**
     * The icon shown on each domain badge, so colour is never the only signal. Hidden from screen readers,
     * which read the text label.
     */
    static final class DomainGlyphs {

        static final Map<String, String> BY_DOMAIN =
                Map.of(
                        ComplaintDomain.ELECTRICAL.name(), "⚡",
                        ComplaintDomain.CIVIL.name(), "🔧",
                        ComplaintDomain.RO_PLANT.name(), "💧",
                        ComplaintDomain.NETWORK.name(), "🌐",
                        ComplaintDomain.UNCLASSIFIED.name(), "❓");

        private DomainGlyphs() {}
    }
}
