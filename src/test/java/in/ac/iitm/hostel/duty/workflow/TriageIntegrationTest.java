package in.ac.iitm.hostel.duty.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import in.ac.iitm.hostel.duty.audit.domain.AuditEvent;
import in.ac.iitm.hostel.duty.audit.infrastructure.AuditEventRepository;
import in.ac.iitm.hostel.duty.identity.domain.AppUser;
import in.ac.iitm.hostel.duty.identity.infrastructure.AppUserRepository;
import in.ac.iitm.hostel.duty.support.AbstractIntegrationTest;
import in.ac.iitm.hostel.duty.support.Fixtures;
import in.ac.iitm.hostel.duty.workflow.application.ComplaintIntake;
import in.ac.iitm.hostel.duty.workflow.application.ComplaintListItem;
import in.ac.iitm.hostel.duty.workflow.application.ComplaintQueryService;
import in.ac.iitm.hostel.duty.workflow.application.IngestedComplaint;
import in.ac.iitm.hostel.duty.workflow.application.NewComplaint;
import in.ac.iitm.hostel.duty.workflow.application.TriageService;
import in.ac.iitm.hostel.duty.workflow.domain.ClassificationMethod;
import in.ac.iitm.hostel.duty.workflow.domain.Complaint;
import in.ac.iitm.hostel.duty.workflow.domain.ComplaintDomain;
import in.ac.iitm.hostel.duty.workflow.domain.ComplaintStatus;
import in.ac.iitm.hostel.duty.workflow.infrastructure.ComplaintRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;

/**
 * Classification at ingest, and the manager's correction afterwards (sections 5.5, 10.3).
 *
 * <p>Every complaint is created through {@link ComplaintIntake} — the workflow module's one
 * published entry point, which the CSV fallback and the Sheets poller both reconcile through
 * (section 5.8). Driving it directly here is what makes "classification happens on ingest, for
 * both sources" a property of one call rather than a claim about two; {@code
 * CsvIngestionIntegrationTest} covers the CSV end of the same path over HTTP-shaped input.
 *
 * <p>The headline behaviour is the last two tests: what the classifier could not settle reaches
 * the manager as work to do, and the manager's answer is recorded as theirs.
 */
@SpringBootTest
@WithMockUser(roles = "MANAGER")
class TriageIntegrationTest extends AbstractIntegrationTest {

    @Autowired private ComplaintIntake intake;
    @Autowired private ComplaintQueryService queries;
    @Autowired private TriageService triage;
    @Autowired private ComplaintRepository complaints;
    @Autowired private AuditEventRepository auditEvents;
    @Autowired private AppUserRepository users;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private Complaint ingest(String rawText) {
        IngestedComplaint ingested =
                intake.record(
                        new NewComplaint(
                                rawText,
                                Instant.parse("2026-05-01T06:00:00Z"),
                                null,
                                null,
                                null,
                                null,
                                "214",
                                null));
        return complaints.findById(ingested.id()).orElseThrow();
    }

    private List<AuditEvent> historyOf(Complaint complaint) {
        return auditEvents.findByEntityTypeAndEntityIdOrderByOccurredAtAsc("complaint", complaint.getId());
    }

    @Test
    @DisplayName("a confidently classified complaint is created with its domain and RULES")
    void classificationHappensOnIngest() {
        Complaint complaint = ingest("Water leaking from the bathroom ceiling");

        assertThat(complaint.getDomain()).isEqualTo(ComplaintDomain.CIVIL);
        assertThat(complaint.getClassifiedBy()).isEqualTo(ClassificationMethod.RULES);
        assertThat(complaint.getDomainConfidence()).isEqualByComparingTo(BigDecimal.ONE);
        // No dispatcher yet, so a classified complaint waits at NEW for the manager to assign it.
        assertThat(complaint.getStatus()).isEqualTo(ComplaintStatus.NEW);
    }

    @Test
    @DisplayName("what the gate refuses lands in triage as UNCLASSIFIED, through transition T3")
    void ambiguityLandsInTriage() {
        Complaint complaint = ingest("water coming from ceiling fan");

        assertThat(complaint.getDomain()).isEqualTo(ComplaintDomain.UNCLASSIFIED);
        assertThat(complaint.getClassifiedBy()).isEqualTo(ClassificationMethod.FALLBACK);
        assertThat(complaint.getStatus()).isEqualTo(ComplaintStatus.TRIAGE);

        // The status moved through the machine, not around it: T3's row is in the audit log with
        // its reason, which is what a second write path to `complaint.status` would not produce.
        assertThat(historyOf(complaint))
                .extracting(AuditEvent::getEventType, AuditEvent::getFromState, AuditEvent::getToState)
                .containsExactly(
                        tuple("COMPLAINT_INGESTED", null, "NEW"), tuple("AUTO_DISPATCH", "NEW", "TRIAGE"));
    }

    @Test
    @DisplayName("the triage queue shows exactly what the classifier could not place")
    void theTriageQueueListsUnclassifiedComplaints() {
        Complaint triaged = ingest("water coming from ceiling fan");
        Complaint classified = ingest("No water in the RO plant since yesterday");

        List<Long> waiting = queries.listTriage().stream().map(ComplaintListItem::id).toList();

        assertThat(waiting).contains(triaged.getId()).doesNotContain(classified.getId());
    }

    @Test
    @DisplayName("a manager's one-click correction sets the domain, marks it MANUAL and is audited")
    void aManagerCorrectsTheDomain() {
        AppUser manager = users.saveAndFlush(Fixtures.manager());
        Fixtures.signIn(manager);
        Complaint complaint = ingest("water coming from ceiling fan");

        triage.classify(complaint.getId(), ComplaintDomain.ELECTRICAL);

        Complaint corrected = complaints.findById(complaint.getId()).orElseThrow();
        assertThat(corrected.getDomain()).isEqualTo(ComplaintDomain.ELECTRICAL);
        assertThat(corrected.getClassifiedBy()).isEqualTo(ClassificationMethod.MANUAL);
        // The classifier's number no longer describes the domain on the record, so it is cleared.
        assertThat(corrected.getDomainConfidence()).isNull();
        // Still in triage: the domain is set, but nobody has been given the work yet (T4).
        assertThat(corrected.getStatus()).isEqualTo(ComplaintStatus.TRIAGE);

        AuditEvent correction =
                historyOf(corrected).stream()
                        .filter(event -> "DOMAIN_CORRECTED".equals(event.getEventType()))
                        .findFirst()
                        .orElseThrow();
        assertThat(correction.getActorId()).isEqualTo(manager.getId());
        assertThat(correction.getDetail())
                .contains("\"from\":\"UNCLASSIFIED\"", "\"to\":\"ELECTRICAL\"", "\"classifiedBy\":\"MANUAL\"");
    }
}
