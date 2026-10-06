package duty.workflow.application;

import java.time.Instant;
import java.util.Objects;

/**
 * The data ingestion passes in to create a complaint. Plain values only, so ingestion never touches the
 * {@code Complaint} entity.
 *
 * @param rawText the resident's wording, exactly as typed
 * @param reportedAt the form timestamp, in UTC
 * @param reporterContact the resident's email; purged after 90 days
 * @param reporterRoom the room number, or {@code null} (goes to triage)
 * @param reportedDomain the category chosen on the form; anything unrecognised goes to triage
 * @param sourceRowId the source row this complaint came from
 */
public record NewComplaint(
        String rawText,
        Instant reportedAt,
        String reporterName,
        String reporterRollNo,
        String reporterPhone,
        String reporterContact,
        String reporterRoom,
        String reportedDomain,
        Long sourceRowId) {

    public NewComplaint {
        Objects.requireNonNull(rawText, "rawText");
        Objects.requireNonNull(reportedAt, "reportedAt");
    }

    /** Leaves out the student's details and the complaint text, so they cannot reach a log. */
    @Override
    public String toString() {
        return "NewComplaint[reportedAt=" + reportedAt + ", sourceRowId=" + sourceRowId + "]";
    }
}
