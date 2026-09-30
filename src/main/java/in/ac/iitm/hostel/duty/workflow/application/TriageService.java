package in.ac.iitm.hostel.duty.workflow.application;

import in.ac.iitm.hostel.duty.audit.application.AuditDetail;
import in.ac.iitm.hostel.duty.audit.application.AuditEntry;
import in.ac.iitm.hostel.duty.audit.application.AuditTrail;
import in.ac.iitm.hostel.duty.identity.application.CurrentUser;
import in.ac.iitm.hostel.duty.workflow.domain.ClassificationMethod;
import in.ac.iitm.hostel.duty.workflow.domain.Complaint;
import in.ac.iitm.hostel.duty.workflow.domain.ComplaintDomain;
import in.ac.iitm.hostel.duty.workflow.infrastructure.ComplaintRepository;
import java.time.Instant;
import java.util.Objects;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The manager's one-click correction from the triage panel (sections 5.5, 10.3).
 *
 * <p>Manager-only, and the {@code @PreAuthorize} is on the service rather than only on the
 * controller (section 11.3): setting a complaint's domain is a manager capability, and a check
 * that lives in a controller is one new caller away from being bypassed.
 *
 * <p><strong>This writes the domain and not the status.</strong> The complaint stays in
 * {@code TRIAGE} until somebody is given the work, which is transition T4 and belongs to
 * {@link WorkflowService} — the only write path to {@code complaint.status} (section 4.1). The two
 * halves of T4's side-effect column are deliberately separable here because section 10.3's triage
 * card separates them: four domain buttons, and an assign control beside them. A manager who knows
 * it is electrical but not yet who should go can press one and not the other.
 *
 * <p>The correction is recorded. Section 5.7 is why it matters beyond the audit trail: every
 * triage decision is a {@code (text, human label)} pair, and enough of them are the labelled
 * corpus that would make an OpenNLP second stage viable later.
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
     * Sets the domain the classifier could not settle, as {@code classified_by = MANUAL}.
     *
     * <p>The SLA due date is computed here and not at intake, because until now there was no target
     * to compute it against: section 8.2's table has a row per domain and {@code UNCLASSIFIED} is
     * not one of them. The window runs from {@code reportedAt} all the same — the resident should not
     * be charged for the time the complaint spent waiting in triage, nor credited for it.
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
