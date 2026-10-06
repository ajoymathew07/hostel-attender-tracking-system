package duty.workflow.application;

import duty.workflow.domain.ClassificationMethod;
import duty.workflow.domain.ComplaintDomain;
import duty.workflow.domain.ComplaintStatus;
import duty.workflow.domain.Priority;
import java.time.Instant;

/**
 * One row of the manager's complaint list. Includes the student's details, which the manager may always
 * see.
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
        Priority basePriority,
        Instant slaDueAt,
        boolean slaOverdue,
        ComplaintStatus status,
        Long assigneeId) {

    /**
     * Whether the priority was raised by ageing rather than set at intake, so the list can show "P1 (aged
     * from P3)".
     */
    public boolean aged() {
        return priority != basePriority;
    }

    /** Leaves out the student's name, roll number and phone. */
    @Override
    public String toString() {
        return "ComplaintListItem[" + publicRef + ", " + status + ", " + priority + "]";
    }
}
