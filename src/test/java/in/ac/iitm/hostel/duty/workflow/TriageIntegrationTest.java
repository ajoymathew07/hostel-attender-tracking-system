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
 * The domain the resident chose, and the manager's correction afterwards (sections 5.4, 10.3).
 *
 * <p>Every complaint is created through {@link ComplaintIntake} — the workflow module's one
 * published entry point, which the CSV fallback and the Sheets poller both reconcile through
 * (section 5.8). Driving it directly here is what makes "the form's answer is read on ingest, for
 * both sources" a property of one call rather than a claim about two; {@code
 * CsvIngestionIntegrationTest} covers the CSV end of the same path over HTTP-shaped input.
 *
 * <p>The headline behaviour is the last two tests: what the resident could not place reaches the
 * manager as work to do, and the manager's answer is recorded as theirs.
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

    private Complaint ingest(String rawText, String reportedDomain) {
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
                                reportedDomain,
                                null));
        return complaints.findById(ingested.id()).orElseThrow();
    }

    private List<AuditEvent> historyOf(Complaint complaint) {
        return auditEvents.findByEntityTypeAndEntityIdOrderByOccurredAtAsc("complaint", complaint.getId());
    }

    @Test
    @DisplayName("the resident's answer is the domain, and it is recorded as theirs")
    void theFormsAnswerIsTheDomain() {
        Complaint complaint = ingest("Water leaking from the bathroom ceiling", "Civil");

        assertThat(complaint.getDomain()).isEqualTo(ComplaintDomain.CIVIL);
        assertThat(complaint.getClassifiedBy()).isEqualTo(ClassificationMethod.REPORTED);
        // Where it went next is the dispatcher's business and AutoDispatchIntegrationTest's
        // subject. What matters here is that a placed complaint does not stay at NEW: T2 and T3
        // run inside the same transaction as the intake.
        assertThat(complaint.getStatus()).isNotEqualTo(ComplaintStatus.NEW);
    }

    @Test
    @DisplayName("LAN is the form's word and NETWORK is the database's; both mean the same floor call")
    void theFormsVocabularyIsNotTheDatabases() {
        assertThat(ingest("wifi down in my room", "LAN").getDomain())
                .isEqualTo(ComplaintDomain.NETWORK);
        assertThat(ingest("no drinking water", "RO").getDomain())
                .isEqualTo(ComplaintDomain.RO_PLANT);
        assertThat(ingest("fan not working", " electrical ").getDomain())
                .describedAs("case and space are the resident's typing, not their meaning")
                .isEqualTo(ComplaintDomain.ELECTRICAL);
    }

    @Test
    @DisplayName("\"Others\" lands in triage as UNCLASSIFIED, through transition T3")
    void othersLandsInTriage() {
        Complaint complaint = ingest("water coming from ceiling fan", "Others");

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
    @DisplayName("an unanswered question is the same as Others — and so is a word nobody mapped")
    void anythingUnrecognisedLandsInTriage() {
        assertThat(ingest("there is a problem", null).getDomain())
                .isEqualTo(ComplaintDomain.UNCLASSIFIED);
        assertThat(ingest("lift is stuck", "Lift / Elevator").getDomain())
                .describedAs("a Form option nobody mapped fills the triage queue, it does not guess")
                .isEqualTo(ComplaintDomain.UNCLASSIFIED);
    }

    @Test
    @DisplayName("the triage queue shows exactly what the resident could not place")
    void theTriageQueueListsUnclassifiedComplaints() {
        Complaint triaged = ingest("water coming from ceiling fan", "Others");
        Complaint classified = ingest("No water in the RO plant since yesterday", "RO");

        List<Long> waiting = queries.listTriage().stream().map(ComplaintListItem::id).toList();

        assertThat(waiting).contains(triaged.getId()).doesNotContain(classified.getId());
    }

    @Test
    @DisplayName("a manager's one-click correction sets the domain, marks it MANUAL and is audited")
    void aManagerCorrectsTheDomain() {
        AppUser manager = users.saveAndFlush(Fixtures.manager());
        Fixtures.signIn(manager);
        Complaint complaint = ingest("water coming from ceiling fan", "Others");

        triage.classify(complaint.getId(), ComplaintDomain.ELECTRICAL);

        Complaint corrected = complaints.findById(complaint.getId()).orElseThrow();
        assertThat(corrected.getDomain()).isEqualTo(ComplaintDomain.ELECTRICAL);
        assertThat(corrected.getClassifiedBy()).isEqualTo(ClassificationMethod.MANUAL);
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
