package duty.analytics.web;

import duty.analytics.application.AnalyticsService;
import java.time.Instant;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** The manager's analytics page (section 8.4): resolution time, bottlenecks, alerts and the export. */
@Controller
class AnalyticsController {

    private final AnalyticsService analytics;

    AnalyticsController(AnalyticsService analytics) {
        this.analytics = analytics;
    }

    @GetMapping("/analytics")
    String page(Model model) {
        Instant now = Instant.now();
        model.addAttribute("domains", analytics.byDomain(now.minus(AnalyticsService.WINDOW), now));
        model.addAttribute("alerts", analytics.openAlerts());
        return "analytics/analytics";
    }

    @PostMapping("/analytics/scan")
    String scan(RedirectAttributes redirect) {
        redirect.addFlashAttribute("message", "Checks ran: " + analytics.runChecksNow() + " alert(s) raised or refreshed.");
        return "redirect:/analytics";
    }

    @PostMapping("/analytics/alerts/{alertId}/acknowledge")
    String acknowledge(@PathVariable long alertId, RedirectAttributes redirect) {
        analytics.acknowledge(alertId);
        redirect.addFlashAttribute("message", "Alert acknowledged.");
        return "redirect:/analytics";
    }
}
