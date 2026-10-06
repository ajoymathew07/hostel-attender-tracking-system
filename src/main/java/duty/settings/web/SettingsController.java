package duty.settings.web;

import duty.dispatch.application.DispatchSettings;
import duty.dispatch.application.DispatchWeights;
import duty.settings.application.SettingsStore;
import duty.workflow.application.SlaConfiguration;
import duty.workflow.application.SlaSettings;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * {@code /settings}: the dispatch weights and SLA windows the manager may change.
 *
 * <p>Validation is left to the owning services. The floor-proximity table is not editable because only
 * the order of its values matters.
 */
@Controller
@PreAuthorize("hasRole('MANAGER')")
class SettingsController {

    private static final String VIEW = "settings/settings";
    private static final String REDIRECT = "redirect:/settings";

    private final DispatchSettings dispatch;
    private final SlaSettings sla;
    private final SettingsStore store;

    SettingsController(DispatchSettings dispatch, SlaSettings sla, SettingsStore store) {
        this.dispatch = dispatch;
        this.sla = sla;
        this.store = store;
    }

    @GetMapping("/settings")
    String show(Model model) {
        model.addAttribute("form", currentValues());
        return populate(model);
    }

    /**
     * Validates every section first, then saves everything in one transaction, so a bad value changes
     * nothing.
     */
    @PostMapping("/settings")
    String save(@ModelAttribute("form") SettingsForm form, RedirectAttributes flash) {
        Map<String, String> edits = new LinkedHashMap<>();
        edits.putAll(dispatch.edits(form.getFloorWeight(), form.getLoadWeight(), form.getLoadCap()));
        edits.putAll(sla.edits(form.getSlaHours(), form.getDayStart(), form.getDayEnd()));

        store.save(edits);
        flash.addFlashAttribute(
                "message", "Saved. The next complaint to arrive is handled with these values.");
        return REDIRECT;
    }

    /** What the screen needs besides the form itself: the labels, and what is fixed. */
    private String populate(Model model) {
        SlaConfiguration configuration = sla.configuration();
        model.addAttribute("domains", configuration.domains());
        model.addAttribute("priorities", configuration.priorities());
        model.addAttribute("zone", configuration.zone());
        return VIEW;
    }

    private SettingsForm currentValues() {
        DispatchWeights weights = dispatch.current();
        SlaConfiguration configuration = sla.configuration();

        SettingsForm form = new SettingsForm();
        form.setFloorWeight(weights.floor());
        form.setLoadWeight(weights.load());
        form.setLoadCap(weights.loadCap());
        form.setDayStart(configuration.dayStart());
        form.setDayEnd(configuration.dayEnd());
        form.setSlaHours(new LinkedHashMap<>(configuration.cells()));
        return form;
    }

    /** Shows a refused value as a message on the screen instead of an error page. */
    @ExceptionHandler(IllegalArgumentException.class)
    String refused(IllegalArgumentException refusal, RedirectAttributes flash) {
        flash.addFlashAttribute("error", "Nothing was saved: " + refusal.getMessage());
        return REDIRECT;
    }
}
