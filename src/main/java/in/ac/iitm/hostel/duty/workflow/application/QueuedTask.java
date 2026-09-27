package in.ac.iitm.hostel.duty.workflow.application;

import in.ac.iitm.hostel.duty.workflow.domain.ComplaintDomain;
import in.ac.iitm.hostel.duty.workflow.domain.ComplaintStatus;
import in.ac.iitm.hostel.duty.workflow.domain.Priority;
import java.time.Instant;

/**
 * One card on the attender's queue (section 10.2).
 *
 * <p>The three student fields are {@code null} once the 7-day post-resolution window has closed
 * (section 11.4). They are nulled when the record is built, not hidden in the template: a value
 * that never enters the model cannot be leaked by a later screen, a future JSON endpoint or a
 * debug dump of the model map.
 *
 * <p>No {@code nextAction} field. Section 10.2's one-primary-action-per-task is driven by the
 * state machine's {@code nextActions} (section 9.3), which is PR 11; until then the one action on
 * a card is opening it.
 */
public record QueuedTask(
        long taskId,
        String publicRef,
        String rawText,
        ComplaintDomain domain,
        Priority priority,
        ComplaintStatus status,
        String room,
        Instant slaDueAt,
        Instant assignedAt,
        String studentName,
        String studentRollNo,
        String studentPhone) {

    /** Whether the 7-day window is still open, which is what the screen asks (section 11.4). */
    public boolean hasStudentContact() {
        return studentName != null || studentRollNo != null || studentPhone != null;
    }

    /**
     * The reference, not the resident. Section 11.4 keeps the student's name, roll number and
     * phone number out of logs and error messages, and {@code toString} is how they get there.
     */
    @Override
    public String toString() {
        return "QueuedTask[" + taskId + ", " + publicRef + ", " + status + ", " + priority + "]";
    }
}
