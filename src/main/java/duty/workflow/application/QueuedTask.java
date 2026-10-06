package duty.workflow.application;

import duty.workflow.domain.ComplaintDomain;
import duty.workflow.domain.ComplaintStatus;
import duty.workflow.domain.Priority;
import java.time.Instant;

/**
 * One card on the attender's queue.
 *
 * <p>Student details are {@code null} once the 7-day window after resolution has passed; they are
 * removed here so they never reach the page. The {@code can...} methods tell the template which single
 * action to show; the state machine still enforces it.
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

    /** Whether ageing raised this priority. */
    public boolean aged() {
        return priority != basePriority;
    }

    /** Can start work (T7). */
    public boolean canStart() {
        return status == ComplaintStatus.ASSIGNED;
    }

    /** Can report what was found on site: a defect or nothing wrong (T9, T10). */
    public boolean canReportInspection() {
        return status == ComplaintStatus.INSPECTING;
    }

    /** Can enter or correct the docket number (T11). */
    public boolean canFileDocket() {
        return status == ComplaintStatus.AWAITING_CMS || status == ComplaintStatus.IN_PROGRESS;
    }

    /** Can resolve: only once a docket is filed (T14). */
    public boolean canResolve() {
        return docketNo != null && !docketNo.isBlank();
    }

    /** Whether the student's contact details may still be shown. */
    public boolean hasStudentContact() {
        return studentName != null || studentRollNo != null || studentPhone != null;
    }

    /** The reference only, so student details cannot reach a log. */
    @Override
    public String toString() {
        return "QueuedTask[" + taskId + ", " + publicRef + ", " + status + ", " + priority + "]";
    }
}
