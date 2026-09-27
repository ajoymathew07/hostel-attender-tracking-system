package in.ac.iitm.hostel.duty.ingestion.web;

import in.ac.iitm.hostel.duty.ingestion.application.IngestionSummary;
import in.ac.iitm.hostel.duty.ingestion.application.SheetsIngestionService;
import java.util.Optional;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * "Sync Now" — the manager's manual wake-and-sync path (section 2.5, point 2).
 *
 * <p>A POST that redirects, so a refresh cannot re-run it. Re-running would in fact be harmless,
 * since {@code response_key} collides and nothing is duplicated — but "harmless" is not a reason
 * to make a refresh do work.
 *
 * <p>The {@code @PreAuthorize} here is defence in depth; the control is the identical rule on
 * {@link SheetsIngestionService#syncNow()} (section 11.3).
 */
@Controller
@PreAuthorize("hasRole('MANAGER')")
class SheetsSyncController {

    private static final String REDIRECT = "redirect:/complaints";

    private final SheetsIngestionService sheets;

    SheetsSyncController(SheetsIngestionService sheets) {
        this.sheets = sheets;
    }

    @PostMapping("/complaints/sync")
    String sync(RedirectAttributes flash) {
        Optional<IngestionSummary> outcome = sheets.syncNow();

        if (outcome.isEmpty()) {
            flash.addFlashAttribute(
                    "error",
                    "The response sheet is not configured on this instance. Upload the CSV instead.");
        } else if (outcome.get().failed()) {
            flash.addFlashAttribute("error", "That sync did not run: " + outcome.get().failureReason());
        } else {
            flash.addFlashAttribute("message", describe(outcome.get()));
        }
        return REDIRECT;
    }

    /**
     * Counts, not a bare "done". "0 new, 37 already held" is the sentence that tells the manager
     * whether the sheet is actually being read.
     */
    private static String describe(IngestionSummary summary) {
        String outcome =
                summary.rowsCreated()
                        + " new complaint(s) from "
                        + summary.rowsSeen()
                        + " row(s); "
                        + summary.rowsSkipped()
                        + " already held or unreadable";
        return summary.failureReason() == null ? outcome : outcome + ". " + summary.failureReason();
    }
}
