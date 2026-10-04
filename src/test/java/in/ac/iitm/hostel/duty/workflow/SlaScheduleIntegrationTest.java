package in.ac.iitm.hostel.duty.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import in.ac.iitm.hostel.duty.support.AbstractIntegrationTest;
import in.ac.iitm.hostel.duty.workflow.application.ComplaintIntake;
import in.ac.iitm.hostel.duty.workflow.application.IngestedComplaint;
import in.ac.iitm.hostel.duty.workflow.application.NewComplaint;
import in.ac.iitm.hostel.duty.workflow.application.PriorityService;
import in.ac.iitm.hostel.duty.workflow.application.TriageService;
import in.ac.iitm.hostel.duty.workflow.domain.Complaint;
import in.ac.iitm.hostel.duty.workflow.domain.ComplaintDomain;
import in.ac.iitm.hostel.duty.workflow.domain.Priority;
import in.ac.iitm.hostel.duty.workflow.infrastructure.ComplaintRepository;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;

/**
 * Base priority and the initial SLA due date, as section 4.5's table gives them (transition T1).
 *
 * <p>Every complaint here is created through {@link ComplaintIntake}, the module's one entry point,
 * so what is asserted is what a real ingested row gets rather than what a hand-built aggregate can
 * be given.
 *
 * <p>All complaints are reported at {@code 2026-05-01T06:00:00Z}, which is <strong>11:30 IST</strong>
 * — the middle of OQ-6's 08:00–20:00 working day — so the figures below are the table's windows and
 * nothing else. The overnight case is the one test that starts outside the window.
 */
@SpringBootTest
@WithMockUser(roles = "MANAGER")
class SlaScheduleIntegrationTest extends AbstractIntegrationTest {

    /** 11:30 IST, well inside the working day. */
    private static final Instant MIDDAY = Instant.parse("2026-05-01T06:00:00Z");

    @Autowired private ComplaintIntake intake;
    @Autowired private ComplaintRepository complaints;
    @Autowired private TriageService triage;
    @Autowired private PriorityService priorities;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("a safety keyword sets P1, and P1 electrical is due in 4 working hours")
    void aSafetyKeywordIsP1() {
        Complaint complaint = ingest("Getting a shock from the geyser switch", "Electrical");

        assertThat(complaint.getDomain()).isEqualTo(ComplaintDomain.ELECTRICAL);
        assertThat(complaint.getPriority()).isEqualTo(Priority.P1);
        assertThat(complaint.getBasePriority()).isEqualTo(Priority.P1);
        // 11:30 IST + 4 working hours = 15:30 IST.
        assertThat(complaint.getSlaDueAt()).isEqualTo(Instant.parse("2026-05-01T10:00:00Z"));
    }

    @Test
    @DisplayName("a near-miss spelling is not P1 — it is an ordinary P2 with an 8-hour window")
    void aNearMissSpellingIsNotP1() {
        Complaint complaint = ingest("Getting a shok from the geyser switch", "Electrical");

        assertThat(complaint.getDomain()).isEqualTo(ComplaintDomain.ELECTRICAL);
        assertThat(complaint.getPriority())
                .describedAs("a false P1 from a misspelling would distort the whole queue")
                .isEqualTo(Priority.P2);
        // 11:30 IST + 8 working hours = 19:30 IST, still inside the same working day.
        assertThat(complaint.getSlaDueAt()).isEqualTo(Instant.parse("2026-05-01T14:00:00Z"));
    }

    @Test
    @DisplayName("the due date matches section 8.2's table for the complaint's domain and priority")
    void theDueDateMatchesTheTable() {
        // RO Plant P2, 12 working hours: 8.5 left on 1 May, then 3.5 from 08:00 on 2 May.
        assertThat(ingest("The water purifier on the second floor is broken", "RO"))
                .satisfies(complaint -> assertThat(complaint.getDomain()).isEqualTo(ComplaintDomain.RO_PLANT))
                .satisfies(
                        complaint ->
                                assertThat(complaint.getSlaDueAt())
                                        .isEqualTo(Instant.parse("2026-05-02T06:00:00Z")));

        // Civil P2, 24 working hours: two full working days beyond the 8.5 left on the first.
        assertThat(ingest("Water leaking from the bathroom ceiling", "Civil"))
                .satisfies(complaint -> assertThat(complaint.getDomain()).isEqualTo(ComplaintDomain.CIVIL))
                .satisfies(
                        complaint ->
                                assertThat(complaint.getSlaDueAt())
                                        .isEqualTo(Instant.parse("2026-05-03T06:00:00Z")));
    }

    @Test
    @DisplayName("an overnight complaint does not burn its SLA while nobody is on duty")
    void anOvernightComplaintDoesNotBurnItsSla() {
        // 23:30 IST on 1 May. Wall-clock arithmetic would have this due at 07:30 the next morning,
        // before the working day starts and before anybody could have acted on it.
        Complaint complaint =
                ingestAt("Tube light flickering in the common room", "Electrical", Instant.parse("2026-05-01T18:00:00Z"));

        assertThat(complaint.getSlaDueAt())
                .describedAs("the clock starts at 08:00 IST and runs 8 working hours to 16:00 IST")
                .isEqualTo(Instant.parse("2026-05-02T10:30:00Z"));
    }

    @Test
    @DisplayName("a complaint the resident could not place has no due date until a manager sets one")
    void triageGivesTheDueDate() {
        Complaint complaint = ingest("water coming from ceiling fan", "Others");

        assertThat(complaint.getDomain()).isEqualTo(ComplaintDomain.UNCLASSIFIED);
        assertThat(complaint.getSlaDueAt())
                .describedAs("section 8.2 has no target for a domain nobody has settled")
                .isNull();

        triage.classify(complaint.getId(), ComplaintDomain.ELECTRICAL);

        assertThat(reload(complaint).getSlaDueAt())
                .describedAs("measured from reportedAt, so the wait in triage costs the resident nothing")
                .isEqualTo(Instant.parse("2026-05-01T14:00:00Z"));
    }

    @Test
    @DisplayName("a manager downgrade is the only source of P3, and it recomputes the due date")
    void aManagerDowngradeIsP3() {
        Complaint complaint = ingest("Tube light flickering in the common room", "Electrical");

        priorities.downgrade(complaint.getId());

        Complaint downgraded = reload(complaint);
        assertThat(downgraded.getPriority()).isEqualTo(Priority.P3);
        assertThat(downgraded.getBasePriority())
                .describedAs("a judgement about the work, so the base moves too and aging can raise it")
                .isEqualTo(Priority.P3);
        // 72 working hours at 12 a day: 8.5 on 1 May, five full days, 3.5 on 7 May.
        assertThat(downgraded.getSlaDueAt()).isEqualTo(Instant.parse("2026-05-07T06:00:00Z"));
    }

    private Complaint ingest(String rawText, String reportedDomain) {
        return ingestAt(rawText, reportedDomain, MIDDAY);
    }

    private Complaint ingestAt(String rawText, String reportedDomain, Instant reportedAt) {
        IngestedComplaint ingested =
                intake.record(
                        new NewComplaint(
                                rawText, reportedAt, null, null, null, null, "214", reportedDomain, null));
        return complaints.findById(ingested.id()).orElseThrow();
    }

    private Complaint reload(Complaint complaint) {
        return complaints.findById(complaint.getId()).orElseThrow();
    }
}
