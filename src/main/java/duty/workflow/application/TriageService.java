package duty.workflow.application;

import duty.audit.application.AuditDetail;
import duty.audit.application.AuditEntry;
import duty.audit.application.AuditTrail;
import duty.identity.application.CurrentUser;
import duty.workflow.domain.ClassificationMethod;
import duty.workflow.domain.Complaint;
import duty.workflow.domain.ComplaintDomain;
import duty.workflow.infrastructure.ComplaintRepository;
import java.time.Instant;
import java.util.Objects;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The manager's one-click domain correction from the triage panel. Manager-only.
 *
 * <p>Sets the domain only; the complaint stays in {@code TRIAGE} until someone is assigned, so a
 * manager can set the domain before choosing who goes. The change is audited.
 */
@Service
@Transactional
@PreAuthorize("hasRole('MANAGER')")
public class TriageService {

    private static final String ENTITY_TYPE = "complaint";

    private final ComplaintRepository complaints;
    private final AuditTrail audit;
    private final SlaClock sla;

    TriageService(ComplaintRepository complaints, AuditTrail audit, SlaClock sla) {
        this.complaints = complaints;
        this.audit = audit;
        this.sla = sla;
    }

    /**
     * Sets the domain ({@code classified_by = MANUAL}) and now computes the SLA due date, still counted
     * from when the resident reported it.
     */
    public void classify(long complaintId, ComplaintDomain domain) {
        Complaint complaint = complaints.findById(complaintId).orElseThrow();
        ComplaintDomain previous = complaint.getDomain();
        complaint.classifyManually(domain, Instant.now());
        sla.schedule(complaint);
        complaints.saveAndFlush(complaint);

        // Ids, names and enum values — never the resident's words (section 11.4).
        audit.record(
                AuditEntry.change(
                        ENTITY_TYPE,
                        complaint.getId(),
                        "DOMAIN_CORRECTED",
                        AuditDetail.of("from", previous.name())
                                .and("to", domain.name())
                                .and("classifiedBy", ClassificationMethod.MANUAL.name())
                                .and("slaDueAt", Objects.toString(complaint.getSlaDueAt(), null))
                                .json(),
                        CurrentUser.idOrNull(),
                        CurrentUser.roleOrNull()));
    }
}
