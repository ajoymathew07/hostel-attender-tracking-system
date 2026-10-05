package in.ac.iitm.hostel.duty.settings.web;

import in.ac.iitm.hostel.duty.dispatch.application.DispatchSettings;
import in.ac.iitm.hostel.duty.dispatch.application.DispatchWeights;
import in.ac.iitm.hostel.duty.settings.application.SettingsStore;
import in.ac.iitm.hostel.duty.workflow.application.SlaConfiguration;
import in.ac.iitm.hostel.duty.workflow.application.SlaSettings;
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
 * {@code /settings} — the numbers the manager may change without a redeploy (section 10.4).
 *
 * <p>Two modules' worth of configuration on one screen, because it is one job: the weights that
 * choose an attender (section 6.2) and the windows that decide what is late (section 8.2). Each
 * is saved by the module that understands it, so this class validates nothing itself — it
 * converts form fields into the services' own vocabulary and lets them refuse.
 *
 * <p>The floor-proximity table is not here and not editable: only the order of its values means
 * anything, so a manager changing one number in isolation would make the scoring worse rather
 * than different.
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
     * One save, or none.
     *
     * <p>Every section is validated before anything is written, so a working day that ends before
     * it starts does not leave the dispatch weights already changed. The three {@code edits} calls
     * refuse; {@code store.save} is the single transaction that commits.
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

    /**
     * A refused value is a sentence on the screen, not a stack trace (section 11.6).
     *
     * <p>Each service states its own refusal — weights that do not sum to 1, a working day that
     * ends before it starts — because each knows why the number matters.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    String refused(IllegalArgumentException refusal, RedirectAttributes flash) {
        flash.addFlashAttribute("error", "Nothing was saved: " + refusal.getMessage());
        return REDIRECT;
    }
}
