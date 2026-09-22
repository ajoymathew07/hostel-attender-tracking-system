package in.ac.iitm.hostel.duty.workflow.application;

import in.ac.iitm.hostel.duty.workflow.domain.ClassificationMethod;
import in.ac.iitm.hostel.duty.workflow.domain.ComplaintDomain;
import in.ac.iitm.hostel.duty.workflow.domain.ComplaintStatus;
import in.ac.iitm.hostel.duty.workflow.domain.Priority;
import java.time.Instant;

/**
 * One row of the manager's complaint list (section 10.3).
 *
 * <p>The manager's view is the one that carries the student's details: section 11.4 retains name,
 * roll number and phone number on the record for the manager, while the attender's view of them
 * expires 7 days after resolution. That window is the attender screen's problem (PR 5); this is
 * the manager's screen, so the fields are present.
 */
public record ComplaintListItem(
        long id,
        String publicRef,
        Instant reportedAt,
        String reporterRoom,
        String reporterName,
        String reporterRollNo,
        String reporterPhone,
        String rawText,
        ComplaintDomain domain,
        ClassificationMethod classifiedBy,
        Priority priority,
        ComplaintStatus status) {

    /** The room is not personal data on its own; the other three are (section 11.4). */
    @Override
    public String toString() {
        return "ComplaintListItem[" + publicRef + ", " + status + ", " + priority + "]";
    }
}
