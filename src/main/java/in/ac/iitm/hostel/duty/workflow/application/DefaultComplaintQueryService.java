package in.ac.iitm.hostel.duty.workflow.application;

import in.ac.iitm.hostel.duty.workflow.domain.Complaint;
import in.ac.iitm.hostel.duty.workflow.infrastructure.ComplaintRepository;
import java.util.List;
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

    DefaultComplaintQueryService(ComplaintRepository complaints) {
        this.complaints = complaints;
    }

    @Override
    public List<ComplaintListItem> listAll() {
        return complaints.findAll(NEWEST_FIRST).stream().map(DefaultComplaintQueryService::toItem).toList();
    }

    @Override
    public long count() {
        return complaints.count();
    }

    private static ComplaintListItem toItem(Complaint complaint) {
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
                complaint.getStatus());
    }
}
