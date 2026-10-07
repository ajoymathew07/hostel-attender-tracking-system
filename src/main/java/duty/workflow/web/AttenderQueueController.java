package duty.workflow.web;

import duty.ledger.application.PointsLedger;
import duty.workflow.application.AttenderQueueService.QueuedTask;
import duty.workflow.application.AttenderQueueService;
import duty.workflow.application.TaskResolutionService.ResolvedTask;
import duty.workflow.application.TaskResolutionService;
import duty.workflow.application.WorkflowService.DocketRequiredException;
import duty.workflow.application.WorkflowService.GuardFailedException;
import duty.workflow.application.WorkflowService.IllegalTransitionException;
import duty.workflow.application.WorkflowService.InvalidDocketException;
import duty.workflow.web.ManagerDashboardController.DomainGlyphs;
import org.springframework.http.HttpStatus;
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
 * The attender's phone screens: {@code /m} (the queue) and {@code /m/tasks/{id}}.
 *
 * <p>No role check: {@code /m} only ever shows the caller's own work, and actions check task ownership
 * on the service.
 */
@Controller
class AttenderQueueController {

    private final AttenderQueueService queue;
    private final TaskResolutionService resolution;
    private final PointsLedger ledger;

    AttenderQueueController(
            AttenderQueueService queue, TaskResolutionService resolution, PointsLedger ledger) {
        this.queue = queue;
        this.resolution = resolution;
        this.ledger = ledger;
    }

    @GetMapping("/m")
    String myQueue(Model model) {
        model.addAttribute("tasks", queue.myQueue());
        // Their own standing, and nobody else's: the leaderboard is the manager's screen
        // (section 11.3).
        model.addAttribute("standing", ledger.myPeriodTotal());
        model.addAttribute("domainGlyphs", DomainGlyphs.BY_DOMAIN);
        return "workflow/queue";
    }

    /** One task's page. A task the caller does not hold is refused before this runs. */
    @GetMapping("/m/tasks/{taskId}")
    String task(@PathVariable long taskId, Model model) {
        QueuedTask task =
                queue.myTask(taskId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("task", task);
        model.addAttribute("domainGlyphs", DomainGlyphs.BY_DOMAIN);
        return "workflow/task";
    }

    /**
     * Start work, defect found, or no defect (T7, T9, T10). One route: the button pressed says which.
     */
    @PostMapping("/m/tasks/{taskId}/start")
    String start(@PathVariable long taskId, RedirectAttributes flash) {
        resolution.startInspection(taskId);
        flash.addFlashAttribute("message", "Inspection started.");
        return "redirect:/m/tasks/" + taskId;
    }

    @PostMapping("/m/tasks/{taskId}/defect")
    String defectConfirmed(@PathVariable long taskId, RedirectAttributes flash) {
        resolution.confirmDefect(taskId);
        flash.addFlashAttribute("message", "Defect confirmed. File the CMS docket next.");
        return "redirect:/m/tasks/" + taskId;
    }

    @PostMapping("/m/tasks/{taskId}/no-defect")
    String noDefectFound(
            @PathVariable long taskId, @RequestParam String note, RedirectAttributes flash) {
        resolution.reportNoDefect(taskId, note);
        flash.addFlashAttribute("message", "Closed with no defect found. No points for this one.");
        return "redirect:/m";
    }

    /** Records the docket number (T11). */
    @PostMapping("/m/tasks/{taskId}/docket")
    String recordDocket(
            @PathVariable long taskId, @RequestParam String docketNo, RedirectAttributes flash) {
        resolution.recordDocket(taskId, docketNo);
        flash.addFlashAttribute("message", "Docket recorded. You can resolve this task now.");
        return "redirect:/m/tasks/" + taskId;
    }

    /** Resolves the task (T14) and returns to the queue. */
    @PostMapping("/m/tasks/{taskId}/resolve")
    String resolve(@PathVariable long taskId, RedirectAttributes flash) {
        ResolvedTask resolved = resolution.resolve(taskId);
        flash.addFlashAttribute(
                "message",
                resolved.publicRef()
                        + " resolved. You have "
                        + resolved.periodTotal()
                        + " points this month.");
        return "redirect:/m";
    }

    /** A docket refusal becomes a message on the queue. */
    @ExceptionHandler(DocketRequiredException.class)
    String docketRequired(RedirectAttributes flash) {
        flash.addFlashAttribute("error", "File the CMS docket number first — no docket, no points.");
        return "redirect:/m";
    }

    @ExceptionHandler(InvalidDocketException.class)
    String invalidDocket(InvalidDocketException failure, RedirectAttributes flash) {
        flash.addFlashAttribute(
                "error", "\"" + failure.getDocketNo() + "\" is not a usable CMS docket number.");
        return "redirect:/m";
    }

    /** Any other refusal (usually a stale screen) becomes a message on the queue. */
    @ExceptionHandler({IllegalTransitionException.class, GuardFailedException.class})
    String refused(RuntimeException refusal, RedirectAttributes flash) {
        flash.addFlashAttribute("error", refusal.getMessage());
        return "redirect:/m";
    }
}
