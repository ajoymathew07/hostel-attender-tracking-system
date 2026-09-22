package in.ac.iitm.hostel.duty.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.ac.iitm.hostel.duty.dispatch.domain.Floor;
import in.ac.iitm.hostel.duty.dispatch.infrastructure.FloorRepository;
import in.ac.iitm.hostel.duty.identity.infrastructure.AppUserRepository;
import in.ac.iitm.hostel.duty.support.AbstractIntegrationTest;
import in.ac.iitm.hostel.duty.support.Fixtures;
import in.ac.iitm.hostel.duty.workflow.domain.ClassificationMethod;
import in.ac.iitm.hostel.duty.workflow.domain.Complaint;
import in.ac.iitm.hostel.duty.workflow.domain.ComplaintDomain;
import in.ac.iitm.hostel.duty.workflow.domain.ComplaintStatus;
import in.ac.iitm.hostel.duty.workflow.domain.Priority;
import in.ac.iitm.hostel.duty.workflow.infrastructure.ComplaintRepository;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * The complaint aggregate (data model section 3.5) and the rules its columns exist to enforce.
 *
 * <p>The guarded transition table itself lands in PR 11. What this PR owes is the state the table
 * will operate on: the docket columns that gate {@code mark_resolved}, the base/current priority
 * pair that makes aging explainable, and the retention columns of section 3.8.
 */
@SpringBootTest
class ComplaintIntegrationTest extends AbstractIntegrationTest {

    @Autowired private ComplaintRepository complaints;
    @Autowired private FloorRepository floors;
    @Autowired private AppUserRepository users;

    @Test
    @DisplayName("uq_complaint_ref: the human-facing reference is unique")
    void publicRefIsUnique() {
        Complaint first = complaints.saveAndFlush(Fixtures.complaint());

        Complaint clash =
                new Complaint(
                        first.getPublicRef(),
                        "Another complaint entirely",
                        Instant.parse("2026-02-02T05:30:00Z"),
                        ComplaintDomain.CIVIL,
                        ClassificationMethod.RULES,
                        Priority.P3,
                        Instant.parse("2026-02-02T06:00:00Z"));

        assertThatThrownBy(() -> complaints.saveAndFlush(clash))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(complaints.findByPublicRef(first.getPublicRef())).isPresent();
    }

    @Test
    @DisplayName("the docket gate: no docket, no resolve — T11 and T14, E15")
    void docketGateGuardsResolution() {
        Complaint complaint = complaints.saveAndFlush(Fixtures.complaint());
        Long filedBy = users.saveAndFlush(Fixtures.attender()).getId();

        assertThat(complaint.hasDocket())
                .describedAs("you cannot collect +2 for work never filed with the Engineering Unit")
                .isFalse();

        complaint.recordDocket("EU/2026/00471", filedBy, Instant.parse("2026-02-02T11:00:00Z"));
        complaints.saveAndFlush(complaint);

        Complaint reloaded = complaints.findById(complaint.getId()).orElseThrow();
        assertThat(reloaded.hasDocket()).isTrue();
        assertThat(reloaded.getDocketNo()).isEqualTo("EU/2026/00471");
        assertThat(reloaded.getDocketFiledBy()).isEqualTo(filedBy);
        assertThat(reloaded.getDocketFiledAt()).isEqualTo(Instant.parse("2026-02-02T11:00:00Z"));
        assertThat(complaints.findByDocketNo("EU/2026/00471")).isPresent();
    }

    @Test
    @DisplayName("a mistyped docket is corrected in place; the audit log keeps both values — E17")
    void docketCanBeCorrected() {
        Complaint complaint = complaints.saveAndFlush(Fixtures.complaint());
        Long filedBy = users.saveAndFlush(Fixtures.attender()).getId();

        complaint.recordDocket("EU/2026/00471", filedBy, Instant.parse("2026-02-02T11:00:00Z"));
        complaint.recordDocket("EU/2026/00417", filedBy, Instant.parse("2026-02-02T11:02:00Z"));
        complaints.saveAndFlush(complaint);

        assertThat(complaints.findById(complaint.getId()).orElseThrow().getDocketNo())
                .describedAs("three columns, not a history table — the audit log holds the old value")
                .isEqualTo("EU/2026/00417");
    }

    @Test
    @DisplayName("priority ages upward and stops at P1, while base_priority is preserved")
    void priorityAgesButBaseIsKept() {
        Complaint complaint =
                complaints.saveAndFlush(
                        new Complaint(
                                Fixtures.unique("CAU-2026"),
                                "Tube light flickering",
                                Instant.parse("2026-02-01T05:30:00Z"),
                                ComplaintDomain.ELECTRICAL,
                                ClassificationMethod.RULES,
                                Priority.P3,
                                Instant.parse("2026-02-01T06:00:00Z")));

        assertThat(complaint.getPriority()).isEqualTo(Priority.P3);

        complaint.escalatePriority(Instant.parse("2026-02-04T06:00:00Z"));
        assertThat(complaint.getPriority()).isEqualTo(Priority.P2);

        complaint.escalatePriority(Instant.parse("2026-02-07T06:00:00Z"));
        assertThat(complaint.getPriority()).isEqualTo(Priority.P1);

        complaint.escalatePriority(Instant.parse("2026-02-10T06:00:00Z"));
        assertThat(complaint.getPriority()).describedAs("capped at P1").isEqualTo(Priority.P1);

        complaints.saveAndFlush(complaint);
        Complaint reloaded = complaints.findById(complaint.getId()).orElseThrow();
        assertThat(reloaded.getBasePriority())
                .describedAs("so the dashboard can show \"P1 (aged from P3)\"")
                .isEqualTo(Priority.P3);
    }

    @Test
    @DisplayName("SLA due date and breach flag drive the overdue sweep")
    void slaColumnsDriveTheSweep() {
        Complaint complaint = complaints.saveAndFlush(Fixtures.complaint());
        complaint.scheduleSla(Instant.parse("2026-02-01T14:00:00Z"));
        complaint = complaints.saveAndFlush(complaint);

        assertThat(complaints.findOverdue(Instant.parse("2026-02-01T15:00:00Z")))
                .extracting(Complaint::getId)
                .contains(complaint.getId());

        complaint.markSlaBreached();
        complaints.saveAndFlush(complaint);

        assertThat(complaints.findOverdue(Instant.parse("2026-02-01T15:00:00Z")))
                .describedAs("already flagged, so it is not swept again")
                .extracting(Complaint::getId)
                .doesNotContain(complaint.getId());
    }

    @Test
    @DisplayName("a manager classifying from triage sets MANUAL and drops the classifier's confidence — T4")
    void manualClassificationReplacesTheGuess() {
        Complaint complaint =
                complaints.saveAndFlush(
                        new Complaint(
                                Fixtures.unique("CAU-2026"),
                                "thanni varala",
                                Instant.parse("2026-02-01T05:30:00Z"),
                                ComplaintDomain.UNCLASSIFIED,
                                ClassificationMethod.FALLBACK,
                                Priority.P2,
                                Instant.parse("2026-02-01T06:00:00Z")));
        complaint.recordClassifierConfidence(new BigDecimal("0.412"));
        complaint = complaints.saveAndFlush(complaint);

        assertThat(complaints.findById(complaint.getId()).orElseThrow().getDomainConfidence())
                .isEqualByComparingTo(new BigDecimal("0.412"));
        assertThat(complaint.getDomain().isDispatchable()).isFalse();

        complaint.classifyManually(ComplaintDomain.RO_PLANT, Instant.parse("2026-02-01T09:00:00Z"));
        complaints.saveAndFlush(complaint);

        Complaint reloaded = complaints.findById(complaint.getId()).orElseThrow();
        assertThat(reloaded.getDomain()).isEqualTo(ComplaintDomain.RO_PLANT);
        assertThat(reloaded.getClassifiedBy()).isEqualTo(ClassificationMethod.MANUAL);
        assertThat(reloaded.getDomainConfidence())
                .describedAs("a human decision has no classifier confidence")
                .isNull();
        assertThat(reloaded.getDomain().isDispatchable()).isTrue();
    }

    @Test
    @DisplayName("ck_conf: confidence outside [0, 1] is rejected before it ever reaches the column")
    void confidenceIsBounded() {
        Complaint complaint = Fixtures.complaint();

        assertThatThrownBy(() -> complaint.recordClassifierConfidence(new BigDecimal("1.001")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> complaint.recordClassifierConfidence(new BigDecimal("-0.001")))
                .isInstanceOf(IllegalArgumentException.class);

        complaint.recordClassifierConfidence(null);
        assertThat(complaint.getDomainConfidence()).isNull();
    }

    @Test
    @DisplayName("closing stamps closed_at; reaching a terminal state is recognised as terminal")
    void closingStampsTheTime() {
        Complaint complaint = complaints.saveAndFlush(Fixtures.complaint());

        complaint.moveTo(ComplaintStatus.RESOLVED, Instant.parse("2026-02-03T10:00:00Z"));
        assertThat(complaint.getClosedAt()).isNull();
        assertThat(complaint.getStatus().isTerminal()).isFalse();

        complaint.moveTo(ComplaintStatus.CLOSED, Instant.parse("2026-02-10T10:00:00Z"));
        complaints.saveAndFlush(complaint);

        Complaint reloaded = complaints.findById(complaint.getId()).orElseThrow();
        assertThat(reloaded.getClosedAt()).isEqualTo(Instant.parse("2026-02-10T10:00:00Z"));
        assertThat(reloaded.getUpdatedAt()).isEqualTo(Instant.parse("2026-02-10T10:00:00Z"));
        assertThat(reloaded.getStatus().isTerminal()).isTrue();
        assertThat(complaints.findByStatusOrderByReportedAtAsc(ComplaintStatus.CLOSED))
                .extracting(Complaint::getId)
                .contains(reloaded.getId());
    }

    @Test
    @DisplayName("reporter details round-trip, and the retention purges of section 3.8 work")
    void reporterDetailsAndRetention() {
        Floor floor = floors.saveAndFlush(new Floor(Fixtures.unique("W"), 2, "Second"));
        Complaint complaint = Fixtures.complaint();
        complaint.recordReporter(
                "Arun Kumar", "CS21B001", "+91-90000-00000", "resident1@example.com", "B-204");
        complaint.locateAt(floor.getId(), "near the corridor tap");
        complaint.linkSourceRow(null);
        complaints.saveAndFlush(complaint);

        Complaint reloaded = complaints.findById(complaint.getId()).orElseThrow();
        assertThat(reloaded.getReporterName()).isEqualTo("Arun Kumar");
        assertThat(reloaded.getReporterRollNo()).isEqualTo("CS21B001");
        assertThat(reloaded.getReporterPhone()).isEqualTo("+91-90000-00000");
        assertThat(reloaded.getReporterContact()).isEqualTo("resident1@example.com");
        assertThat(reloaded.getReporterRoom()).isEqualTo("B-204");
        assertThat(reloaded.getFloorId()).isEqualTo(floor.getId());
        assertThat(reloaded.getLocationNote()).isEqualTo("near the corridor tap");
        assertThat(reloaded.getSourceRowId()).isNull();
        assertThat(reloaded.getRawText()).isEqualTo("No water in the bathroom since morning");
        assertThat(reloaded.getReportedAt()).isEqualTo(Instant.parse("2026-02-01T05:30:00Z"));
        assertThat(reloaded.getCreatedAt()).isEqualTo(Instant.parse("2026-02-01T06:03:00Z"));

        // NFR-9: the resident's email is nulled after 90 days.
        reloaded.purgeReporterContact();
        // Raw text is replaced after a year; aggregates survive.
        reloaded.purgeRawText();
        complaints.saveAndFlush(reloaded);

        Complaint purged = complaints.findById(complaint.getId()).orElseThrow();
        assertThat(purged.getReporterContact()).isNull();
        assertThat(purged.getRawText()).isEqualTo("[purged]");
        assertThat(purged.getReporterName())
                .describedAs("the manager keeps these for oversight; only the view expires")
                .isEqualTo("Arun Kumar");
    }
}
