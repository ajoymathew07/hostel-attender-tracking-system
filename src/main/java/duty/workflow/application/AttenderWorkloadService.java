package duty.workflow.application;

import duty.workflow.domain.Complaint.ComplaintStatus;
import duty.workflow.domain.Complaint.Priority;
import duty.workflow.domain.Complaint;
import duty.workflow.domain.TaskAssignment.AssignmentStatus;
import duty.workflow.domain.TaskAssignment;
import duty.workflow.domain.WorkingCalendar;
import duty.workflow.infrastructure.TaskAssignmentRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Computes attender workload for dispatch from this module's own tables, so dispatch need not read
 * them.
 */
@Service
@Transactional(readOnly = true)
public class AttenderWorkloadService {

    /**
     * States in which a task occupies the attender. {@code ON_HOLD} is left out: they are waiting on the
     * Engineering Unit, not working.
     */
    private static final Set<ComplaintStatus> ACTIVE_WORK =
            EnumSet.of(
                    ComplaintStatus.ASSIGNED,
                    ComplaintStatus.INSPECTING,
                    ComplaintStatus.AWAITING_CMS,
                    ComplaintStatus.IN_PROGRESS);

    private final TaskAssignmentRepository assignments;
    private final ZoneId zone;

    AttenderWorkloadService(TaskAssignmentRepository assignments, WorkingCalendar calendar) {
        this.assignments = assignments;
        this.zone = calendar.zone();
    }

    /** One attender's current load, today's count and when they were last assigned. */
    public AttenderWorkload of(long attenderId, Instant at) {
        return new AttenderWorkload(
                weightedLoad(attenderId),
                assignments.countByAttenderIdAndAssignedAtGreaterThanEqual(attenderId, startOfDay(at)),
                assignments
                        .findFirstByAttenderIdOrderByAssignedAtDesc(attenderId)
                        .map(TaskAssignment::getAssignedAt)
                        .orElse(null));
    }

    /** Sum of priority weights, because three P1 faults are more work than three P3 ones. */
    private int weightedLoad(long attenderId) {
        int load = 0;
        for (TaskAssignment held :
                assignments.findByAttenderIdAndStatus(attenderId, AssignmentStatus.ACTIVE)) {
            Complaint complaint = held.getTask().getComplaint();
            if (complaint != null && ACTIVE_WORK.contains(complaint.getStatus())) {
                load += weightOf(complaint.getPriority());
            }
        }
        return load;
    }

    private static int weightOf(Priority priority) {
        return switch (priority) {
            case P1 -> 3;
            case P2 -> 2;
            case P3 -> 1;
        };
    }

    /** Start of today in the local timezone, not UTC. */
    private Instant startOfDay(Instant at) {
        return LocalDate.ofInstant(at, zone).atStartOfDay(zone).toInstant();
    }

    /**
     * How busy one attender is: the inputs to the dispatch load score and tie-breaks.
     *
     * @param weighted 3 per P1, 2 per P2, 1 per P3 over their active tasks
     * @param assignedToday assignments given today, in the local timezone
     * @param lastAssignedAt {@code null} if never assigned
     */
    public record AttenderWorkload(int weighted, long assignedToday, Instant lastAssignedAt) {}
}
