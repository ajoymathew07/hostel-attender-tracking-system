package in.ac.iitm.hostel.duty.workflow.application;

import in.ac.iitm.hostel.duty.workflow.domain.AssignmentStatus;
import in.ac.iitm.hostel.duty.workflow.domain.Complaint;
import in.ac.iitm.hostel.duty.workflow.domain.ComplaintStatus;
import in.ac.iitm.hostel.duty.workflow.domain.Priority;
import in.ac.iitm.hostel.duty.workflow.domain.TaskAssignment;
import in.ac.iitm.hostel.duty.workflow.domain.WorkingCalendar;
import in.ac.iitm.hostel.duty.workflow.infrastructure.TaskAssignmentRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The workload half of the dispatch heuristic, read from this module's own tables (section 6.2).
 *
 * <p>Published so the dispatch module can score without reaching into {@code workflow} for the
 * assignments themselves (architecture section 2.4, rule 3). It is the only thing dispatch asks
 * this module for.
 */
@Service
@Transactional(readOnly = true)
public class AttenderWorkloadService {

    /**
     * The states in which a task is really occupying the attender.
     *
     * <p>{@code ON_HOLD} is absent, and that is section 6.2's point: the attender is waiting on
     * the Engineering Unit rather than blocked by the work, so held tasks count zero. Resolved and
     * finished complaints are not work either.
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

    /** One attender's current load, today's count and when they were last given anything. */
    public AttenderWorkload of(long attenderId, Instant at) {
        return new AttenderWorkload(
                weightedLoad(attenderId),
                assignments.countByAttenderIdAndAssignedAtGreaterThanEqual(attenderId, startOfDay(at)),
                assignments
                        .findFirstByAttenderIdOrderByAssignedAtDesc(attenderId)
                        .map(TaskAssignment::getAssignedAt)
                        .orElse(null));
    }

    /**
     * {@code L(a) = Σ π(t)} — weighted by priority rather than counting tickets, because three P1
     * electrical faults is not the same workload as three P3 cosmetic complaints.
     */
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

    /** "Today" is the manager's day, not UTC's: the anti-starvation rule is about a shift. */
    private Instant startOfDay(Instant at) {
        return LocalDate.ofInstant(at, zone).atStartOfDay(zone).toInstant();
    }
}
