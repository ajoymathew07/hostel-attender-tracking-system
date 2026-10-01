package in.ac.iitm.hostel.duty.workflow.application;

import in.ac.iitm.hostel.duty.workflow.domain.AssignmentStatus;
import in.ac.iitm.hostel.duty.workflow.domain.Complaint;
import in.ac.iitm.hostel.duty.workflow.domain.ComplaintStatus;
import in.ac.iitm.hostel.duty.workflow.domain.TaskAssignment;
import in.ac.iitm.hostel.duty.workflow.infrastructure.ComplaintRepository;
import in.ac.iitm.hostel.duty.workflow.infrastructure.TaskAssignmentRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The manager's view of every complaint (section 10.3).
 *
 * <p>{@code @PreAuthorize} is on the service, not only on the controller (section 11.3): "view all
 * complaints" is a manager capability, and an attender sees their own assignments alone. A check
 * that lives only in a controller is one new caller away from being bypassed.
 */
@Service
@Transactional(readOnly = true)
@PreAuthorize("hasRole('MANAGER')")
class DefaultComplaintQueryService implements ComplaintQueryService {

    /** Newest report first: the manager is looking for what just arrived (section 10.3). */
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "reportedAt", "id");

    private final ComplaintRepository complaints;
    private final TaskAssignmentRepository assignments;

    DefaultComplaintQueryService(
            ComplaintRepository complaints, TaskAssignmentRepository assignments) {
        this.complaints = complaints;
        this.assignments = assignments;
    }

    @Override
    public List<ComplaintListItem> listAll() {
        Instant now = Instant.now();
        // One query for every live assignment, not one per row: the manager's list is the screen
        // section 10.6 names when it says "no N+1, fetch joins on queue and dashboard".
        Map<Long, Long> assignee = new HashMap<>();
        for (TaskAssignment assignment : assignments.findAllWithComplaint(AssignmentStatus.ACTIVE)) {
            assignee.put(assignment.getTask().getComplaint().getId(), assignment.getAttenderId());
        }
        return complaints.findAll(NEWEST_FIRST).stream()
                .map(complaint -> toItem(complaint, assignee.get(complaint.getId()), now))
                .toList();
    }

    /**
     * No assignee is looked up. A complaint in {@code TRIAGE} has nobody on it by definition —
     * either it never reached {@code ASSIGNED}, or T8 released whoever held it on the way back.
     */
    @Override
    public List<ComplaintListItem> listTriage() {
        return complaints.findByStatusOrderByReportedAtAsc(ComplaintStatus.TRIAGE).stream()
                .map(complaint -> toItem(complaint, null, Instant.now()))
                .toList();
    }

    @Override
    public long count() {
        return complaints.count();
    }

    /**
     * {@code slaOverdue} is computed here rather than read from {@code sla_breached}.
     *
     * <p>The flag is written by the aging sweep, which runs on an interval; the manager looking at
     * the list wants to know whether the due date has passed <em>now</em>. Reading the column would
     * show a complaint as on time for up to one tick after it stopped being so.
     */
    private static ComplaintListItem toItem(Complaint complaint, Long assigneeId, Instant now) {
        return new ComplaintListItem(
                complaint.getId(),
                complaint.getPublicRef(),
                complaint.getReportedAt(),
                complaint.getReporterRoom(),
                complaint.getReporterName(),
                complaint.getReporterRollNo(),
                complaint.getReporterPhone(),
                complaint.getRawText(),
                complaint.getDomain(),
                complaint.getClassifiedBy(),
                complaint.getPriority(),
                complaint.getBasePriority(),
                complaint.getSlaDueAt(),
                complaint.getSlaDueAt() != null && now.isAfter(complaint.getSlaDueAt()),
                complaint.getStatus(),
                assigneeId);
    }
}
