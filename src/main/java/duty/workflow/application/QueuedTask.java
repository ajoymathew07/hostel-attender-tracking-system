package duty.workflow.application;

import duty.workflow.domain.ComplaintDomain;
import duty.workflow.domain.ComplaintStatus;
import duty.workflow.domain.Priority;
import java.time.Instant;

/**
 * One card on the attender's queue (section 10.2).
 *
 * <p>The three student fields are {@code null} once the 7-day post-resolution window has closed
 * (section 11.4). They are nulled when the record is built, not hidden in the template: a value
 * that never enters the model cannot be leaked by a later screen, a future JSON endpoint or a
 * debug dump of the model map.
 *
 * <p>Section 10.2's one-primary-action-per-task is the complaint's state, asked four ways below.
 * The screen may not decide for itself which button to draw: the table is the authority, the
 * refusal in {@code WorkflowService} is what enforces it, and these four are the same answer
 * phrased for a template.
 */
public record QueuedTask(
        long taskId,
        String publicRef,
        String rawText,
        ComplaintDomain domain,
        Priority priority,
        Priority basePriority,
        boolean slaOverdue,
        ComplaintStatus status,
        String room,
        String docketNo,
        Instant slaDueAt,
        Instant assignedAt,
        String studentName,
        String studentRollNo,
        String studentPhone) {

    /** Whether the clock raised this priority rather than intake setting it (section 4.5). */
    public boolean aged() {
        return priority != basePriority;
    }

    /** T7 — the attender is on their way. */
    public boolean canStart() {
        return status == ComplaintStatus.ASSIGNED;
    }

    /** T9 and T10 — the fork on site: a defect, or nothing wrong. */
    public boolean canReportInspection() {
        return status == ComplaintStatus.INSPECTING;
    }

    /** T11 — the number the Engineering Unit gave back, or a correction to it (section 4.3). */
    public boolean canFileDocket() {
        return status == ComplaintStatus.AWAITING_CMS || status == ComplaintStatus.IN_PROGRESS;
    }

    /** Whether the docket gate is open: no docket, no resolve (T14, section 4.3). */
    public boolean canResolve() {
        return docketNo != null && !docketNo.isBlank();
    }

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
