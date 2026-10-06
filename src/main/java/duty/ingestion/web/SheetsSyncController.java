package duty.ingestion.web;

import duty.ingestion.application.IngestionSummary;
import duty.ingestion.application.SheetsIngestionService;
import java.util.Optional;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The manager's "Sync Now" button.
 *
 * <p>Redirects after the POST so a refresh does not re-run it. The real permission check is on
 * {@link SheetsIngestionService#syncNow()}.
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

    /** A summary with counts, so the manager knows the sheet is being read. */
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
