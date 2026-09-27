package in.ac.iitm.hostel.duty.ingestion.web;

import in.ac.iitm.hostel.duty.ingestion.application.CsvUploadService;
import in.ac.iitm.hostel.duty.ingestion.application.IngestionSummary;
import in.ac.iitm.hostel.duty.ingestion.application.UploadedFile;
import in.ac.iitm.hostel.duty.ingestion.domain.UploadRejectedException;
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
 * The drag-and-drop CSV upload on the manager dashboard — Risk 1's Plan B (section 5.8).
 *
 * <p>A POST that redirects, so a refresh cannot re-upload. Re-uploading would in fact be harmless,
 * since {@code response_key} collides and nothing is duplicated — but "harmless" is not a reason to
 * make a refresh do work.
 *
 * <p>The {@code @PreAuthorize} here is defence in depth; the control is the identical rule on
 * {@link CsvUploadService}'s implementation (section 11.3).
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
     * Counts, not a bare "done". The manager's reason for uploading is that something went wrong
     * upstream, so "0 new, 37 already held" is the sentence that tells them whether it worked.
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

    /**
     * A refused upload is a sentence to the manager, never a stack trace (section 11.6).
     *
     * <p>The message names the media type or the limit and nothing else, so there is no route by
     * which a cell of the file could reach the screen.
     */
    @ExceptionHandler(UploadRejectedException.class)
    String rejected(UploadRejectedException refusal, RedirectAttributes flash) {
        flash.addFlashAttribute("error", refusal.getMessage());
        return REDIRECT;
    }

    /**
     * The container's own multipart limit, which is set to the same 5 MB and fires before any of
     * our code runs. Two limits for one rule is deliberate: this one stops the bytes arriving,
     * ours is the one that is tested and that a non-HTTP caller would also meet.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    String tooLarge(RedirectAttributes flash) {
        flash.addFlashAttribute("error", "That file is larger than the 5 MB upload limit");
        return REDIRECT;
    }
}
