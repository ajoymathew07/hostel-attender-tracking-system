package duty.ingestion.web;

import duty.ingestion.application.CsvUploadService;
import duty.ingestion.application.IngestionSummary;
import duty.ingestion.application.UploadedFile;
import duty.ingestion.domain.UploadRejectedException;
import java.io.IOException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The CSV upload on the manager dashboard.
 *
 * <p>Redirects after the POST so a refresh does not re-upload. The real permission check is on
 * {@link CsvUploadService}.
 */
@Controller
@PreAuthorize("hasRole('MANAGER')")
class CsvUploadController {

    private static final String REDIRECT = "redirect:/complaints";

    private final CsvUploadService uploads;

    CsvUploadController(CsvUploadService uploads) {
        this.uploads = uploads;
    }

    @PostMapping("/complaints/upload")
    String upload(@RequestParam("file") MultipartFile file, RedirectAttributes flash) throws IOException {
        IngestionSummary summary =
                uploads.upload(
                        new UploadedFile(file.getOriginalFilename(), file.getContentType(), file.getBytes()));

        if (summary.failed()) {
            flash.addFlashAttribute("error", "That upload was not ingested: " + summary.failureReason());
        } else {
            flash.addFlashAttribute("message", describe(summary));
        }
        return REDIRECT;
    }

    /**
     * A summary with counts, e.g. "0 new, 37 already held", so the manager knows whether it worked.
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

    /** A refused upload becomes a message on the screen; it never quotes the file's contents. */
    @ExceptionHandler(UploadRejectedException.class)
    String rejected(UploadRejectedException refusal, RedirectAttributes flash) {
        flash.addFlashAttribute("error", refusal.getMessage());
        return REDIRECT;
    }

    /** The server's own 5 MB multipart limit, which fires before our code runs. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    String tooLarge(RedirectAttributes flash) {
        flash.addFlashAttribute("error", "That file is larger than the 5 MB upload limit");
        return REDIRECT;
    }
}
