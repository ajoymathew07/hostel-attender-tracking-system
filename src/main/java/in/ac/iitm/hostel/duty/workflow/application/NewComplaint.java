package in.ac.iitm.hostel.duty.workflow.application;

import java.time.Instant;
import java.util.Objects;

/**
 * A complaint about to enter the system — transition T1's payload (section 4.2).
 *
 * <p>This record is the <em>only</em> thing ingestion hands the workflow module. It lives in
 * {@code application} because that is the module's published surface: no other module may reach
 * {@code workflow.domain} for a {@code Complaint} (architecture section 2.4, rule 3), which is
 * also why every field here is a plain value rather than an entity.
 *
 * @param rawText the resident's wording, verbatim
 * @param reportedAt the form timestamp, already UTC
 * @param reporterName optional; {@code null} when the Form does not ask (section 11.4)
 * @param reporterRollNo optional
 * @param reporterPhone optional
 * @param reporterContact the resident's email; purged after 90 days (NFR-9)
 * @param reporterRoom the room number, or {@code null} — unmapped routes to triage
 * @param sourceRowId the observed row this complaint came from
 */
public record NewComplaint(
        String rawText,
        Instant reportedAt,
        String reporterName,
        String reporterRollNo,
        String reporterPhone,
        String reporterContact,
        String reporterRoom,
        Long sourceRowId) {

    public NewComplaint {
        Objects.requireNonNull(rawText, "rawText");
        Objects.requireNonNull(reportedAt, "reportedAt");
    }

    /**
     * Carries no reporter details and no complaint text.
     *
     * <p>The generated record {@code toString} would print the student's name, roll number, phone
     * number and email. This object crosses a module boundary and is the natural thing to put in a
     * log line or to find attached to a stack trace; section 11.4 permits those fields to be
     * stored and shown to the manager, and never to be logged.
     */
    @Override
    public String toString() {
        return "NewComplaint[reportedAt=" + reportedAt + ", sourceRowId=" + sourceRowId + "]";
    }
}
