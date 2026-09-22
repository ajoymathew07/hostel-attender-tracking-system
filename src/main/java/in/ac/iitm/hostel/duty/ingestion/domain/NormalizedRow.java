package in.ac.iitm.hostel.duty.ingestion.domain;

import java.time.Instant;

/**
 * A raw row turned into the fields a complaint carries (section 5.4).
 *
 * <p>{@code rawText} is the description cell <em>verbatim</em>, never modified: the classifier's
 * output is stored alongside the resident's wording, never in place of it, so a misclassification
 * is always recoverable.
 *
 * @param rawText the description cell, untouched
 * @param reportedAt the form timestamp, read in Asia/Kolkata and held as UTC (NFR-7)
 * @param reporterRoom the room number, or {@code null} — an unmapped complaint routes to triage
 * @param reporterName the student's name, or {@code null} when the Form does not ask (section 11.4)
 * @param reporterRollNo the student's roll number, upper-cased; optional
 * @param reporterPhone the student's phone, digits normalised; optional
 * @param reporterContact the resident's email; purged after 90 days (NFR-9)
 * @param categoryHint the resident's own guess at the category, for the classifier in PR 10
 */
public record NormalizedRow(
        String rawText,
        Instant reportedAt,
        String reporterRoom,
        String reporterName,
        String reporterRollNo,
        String reporterPhone,
        String reporterContact,
        String categoryHint) {

    /**
     * Carries no reporter details.
     *
     * <p>Four of this record's components are the student's name, roll number, phone number and
     * email. The generated record {@code toString} would print all four, and this object passes
     * through the ingestion service where a diagnostic log line is one careless edit away. Section
     * 11.4 says those fields may be stored and shown to the manager, and never written to a log.
     */
    @Override
    public String toString() {
        return "NormalizedRow[reportedAt=" + reportedAt + ", room=" + (reporterRoom == null ? "none" : "known") + "]";
    }
}
