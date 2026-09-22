package in.ac.iitm.hostel.duty.workflow.web;

import in.ac.iitm.hostel.duty.workflow.application.AttenderQueueService;
import in.ac.iitm.hostel.duty.workflow.application.QueuedTask;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The attender's phone: {@code /m} and {@code /m/tasks/{id}} (sections 10.2, 10.4).
 *
 * <p>Mobile-only by design — section 10.1 is explicit that this is not the manager's screen at a
 * different breakpoint, and it carries no desktop compromise.
 *
 * <p>No role annotation. {@code /m} shows the caller's own assignments and nothing else, so there
 * is no wider set a role could narrow; the detail view is guarded by {@code @access.ownsTask} on
 * the service, which is the check section 11.3 names. A manager who opens {@code /m} sees an empty
 * queue, which is the truth.
 */
@Controller
class AttenderQueueController {

    private final AttenderQueueService queue;

    AttenderQueueController(AttenderQueueService queue) {
        this.queue = queue;
    }

    @GetMapping("/m")
    String myQueue(Model model) {
        model.addAttribute("tasks", queue.myQueue());
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
}
