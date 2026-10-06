package duty.workflow.application;

import duty.workflow.domain.ClassificationMethod;
import duty.workflow.domain.ComplaintDomain;
import duty.workflow.domain.ComplaintStatus;
import duty.workflow.domain.Priority;
import java.time.Instant;

/**
 * One row of the manager's complaint list (section 10.3).
 *
 * <p>The manager's view is the one that carries the student's details: section 11.4 retains name,
 * roll number and phone number on the record for the manager, while the attender's view of them
 * expires 7 days after resolution. That window is the attender screen's problem; this is the
 * manager's screen, so the fields are present.
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
     * Whether the clock raised this priority rather than intake setting it (section 4.5).
     *
     * <p>{@code base_priority} is kept precisely so the list can say "P1 (aged from P3)": a P1 that
     * the safety keywords produced and a P1 that waited three days are different facts, and a
     * manager deciding what to do next needs to be able to tell them apart.
     */
    public boolean aged() {
        return priority != basePriority;
    }

    /** The room is not personal data on its own; the other three are (section 11.4). */
    @Override
    public String toString() {
        return "ComplaintListItem[" + publicRef + ", " + status + ", " + priority + "]";
    }
}
