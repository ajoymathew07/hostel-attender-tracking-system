package in.ac.iitm.hostel.duty.workflow.web;

import in.ac.iitm.hostel.duty.ledger.application.PointsLedger;
import in.ac.iitm.hostel.duty.workflow.application.AttenderQueueService;
import in.ac.iitm.hostel.duty.workflow.application.DocketRequiredException;
import in.ac.iitm.hostel.duty.workflow.application.GuardFailedException;
import in.ac.iitm.hostel.duty.workflow.application.IllegalTransitionException;
import in.ac.iitm.hostel.duty.workflow.application.InvalidDocketException;
import in.ac.iitm.hostel.duty.workflow.application.QueuedTask;
import in.ac.iitm.hostel.duty.workflow.application.ResolvedTask;
import in.ac.iitm.hostel.duty.workflow.application.TaskResolutionService;
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
 * The attender's phone: {@code /m} and {@code /m/tasks/{id}} (sections 10.2, 10.4).
 *
 * <p>Mobile-only by design — section 10.1 is explicit that this is not the manager's screen at a
 * different breakpoint, and it carries no desktop compromise.
 *
 * <p>No role annotation. {@code /m} shows the caller's own assignments and nothing else, so there
 * is no wider set a role could narrow; the two actions are guarded by ownership on the service,
 * which is the check section 11.3 names. A manager who opens {@code /m} sees an empty queue, which
 * is the truth.
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

    /**
     * The deep-link target for a push notification (section 10.2).
     *
     * <p>A task the caller does not hold is refused by {@code @access.ownsTask} before this method
     * runs, so the only way to reach the empty case is a task id that does not exist.
     */
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
     * Transitions T7, T9 and T10 — the START and DEFECT? steps of section 10.2's flow.
     *
     * <p>One route for the three because they are one screen control each way: the attender taps
     * what happened and the table decides where that lands. The complaint id is not in the URL —
     * the attender's world is tasks, and {@code @access.ownsTask} is keyed on the task.
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

    /** Transition T11: the number the Engineering Unit gave back. */
    @PostMapping("/m/tasks/{taskId}/docket")
    String recordDocket(
            @PathVariable long taskId, @RequestParam String docketNo, RedirectAttributes flash) {
        resolution.recordDocket(taskId, docketNo);
        flash.addFlashAttribute("message", "Docket recorded. You can resolve this task now.");
        return "redirect:/m/tasks/" + taskId;
    }

    /**
     * Transition T14: the +2.
     *
     * <p>Back to the queue rather than to the task, because the assignment is complete and the
     * task has left the queue — the attender's next question is what else is waiting.
     */
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

    /**
     * The two docket refusals, as sentences rather than stack traces (section 11.6).
     *
     * <p>Both land back on the queue: a stale screen is how either one is reached, so the useful
     * next thing to show is the current state of the work.
     */
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

    /**
     * Every other refusal from the table.
     *
     * <p>Reached from a stale tab — the attender left the task open, finished it on another phone,
     * and the old screen still shows the old button. The queue is the useful thing to show next.
     */
    @ExceptionHandler({IllegalTransitionException.class, GuardFailedException.class})
    String refused(RuntimeException refusal, RedirectAttributes flash) {
        flash.addFlashAttribute("error", refusal.getMessage());
        return "redirect:/m";
    }
}
