package duty.ingestion.domain;

import java.time.Instant;

/**
 * A raw row turned into complaint fields.
 *
 * @param rawText the description, exactly as typed
 * @param reportedAt the form timestamp, read as Asia/Kolkata time and stored as UTC
 * @param reporterRoom the room number, or {@code null} (the complaint then goes to triage)
 * @param reporterContact the resident's email; purged after 90 days
 * @param reportedDomain the category chosen on the form, as text; workflow interprets it
 */
public record NormalizedRow(
        String rawText,
        Instant reportedAt,
        String reporterRoom,
        String reporterName,
        String reporterRollNo,
        String reporterPhone,
        String reporterContact,
        String reportedDomain) {

    /** Leaves out the student's details so they cannot reach a log. */
    @Override
    public String toString() {
        return "NormalizedRow[reportedAt=" + reportedAt + ", room=" + (reporterRoom == null ? "none" : "known") + "]";
    }
}
