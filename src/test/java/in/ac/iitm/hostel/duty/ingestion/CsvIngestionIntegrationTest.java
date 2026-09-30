package in.ac.iitm.hostel.duty.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.ac.iitm.hostel.duty.ingestion.application.CsvUploadService;
import in.ac.iitm.hostel.duty.ingestion.application.IngestionSummary;
import in.ac.iitm.hostel.duty.ingestion.application.UploadedFile;
import in.ac.iitm.hostel.duty.ingestion.domain.IngestionRun;
import in.ac.iitm.hostel.duty.ingestion.domain.IngestionStatus;
import in.ac.iitm.hostel.duty.ingestion.domain.SourceRow;
import in.ac.iitm.hostel.duty.ingestion.domain.SourceType;
import in.ac.iitm.hostel.duty.ingestion.domain.UploadRejectedException;
import in.ac.iitm.hostel.duty.ingestion.infrastructure.CsvComplaintSource;
import in.ac.iitm.hostel.duty.ingestion.infrastructure.IngestionRunRepository;
import in.ac.iitm.hostel.duty.ingestion.infrastructure.SourceRowRepository;
import in.ac.iitm.hostel.duty.support.AbstractIntegrationTest;
import in.ac.iitm.hostel.duty.workflow.domain.ClassificationMethod;
import in.ac.iitm.hostel.duty.workflow.domain.Complaint;
import in.ac.iitm.hostel.duty.workflow.domain.ComplaintDomain;
import in.ac.iitm.hostel.duty.workflow.domain.ComplaintStatus;
import in.ac.iitm.hostel.duty.workflow.domain.Priority;
import in.ac.iitm.hostel.duty.workflow.infrastructure.ComplaintRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;

/**
 * CSV ingestion end to end, against real PostgreSQL (test plan section 12.1).
 *
 * <p>The headline property is NFR-1's: <strong>a complaint is never created twice</strong>.
 * Re-uploading the same file is the obvious thing a flustered user does during an outage, so it is
 * the first thing asserted — and the second assertion is that the guarantee rests on {@code
 * uq_source_row} rather than on the application's own lookup, because a check-then-insert is
 * itself a race.
 *
 * <p>Student details in these fixtures are synthetic (section 11.4). No real resident's name, roll
 * number or phone number is ever committed to this repository.
 */
@SpringBootTest
@WithMockUser(roles = "MANAGER")
class CsvIngestionIntegrationTest extends AbstractIntegrationTest {

    private static final String HEADERS =
            "Timestamp,Email Address,Name,Roll Number,Phone Number,Room Number,Describe the issue,Type of issue";

    @Autowired private CsvUploadService uploads;
    @Autowired private ComplaintRepository complaints;
    @Autowired private SourceRowRepository sourceRows;
    @Autowired private IngestionRunRepository runs;

    private static UploadedFile csv(String body) {
        return new UploadedFile("responses.csv", "text/csv", body.getBytes(StandardCharsets.UTF_8));
    }

    private IngestionRun run(IngestionSummary summary) {
        return runs.findById(summary.runId()).orElseThrow();
    }

    private List<Complaint> complaintsFrom(IngestionSummary summary) {
        return sourceRows.findAll().stream()
                .filter(row -> summary.runId() == row.getFirstSeenRunId())
                .map(SourceRow::getComplaintId)
                .filter(java.util.Objects::nonNull)
                .map(id -> complaints.findById(id).orElseThrow())
                .toList();
    }

    // ---- idempotency ----------------------------------------------------

    @Test
    @DisplayName("uploading the same CSV twice creates no duplicate complaints")
    void theSameCsvTwiceCreatesNoDuplicates() {
        String body =
                HEADERS
                        + "\n"
                        + "2026/02/01 09:12:00,one@example.com,Student One,CE21B001,9000000001,214,No water since morning,RO\n"
                        + "2026/02/01 09:30:00,two@example.com,Student Two,CE21B002,9000000002,118,Door latch broken,Civil\n";

        IngestionSummary first = uploads.upload(csv(body));
        long complaintsAfterFirst = complaints.count();

        IngestionSummary second = uploads.upload(csv(body));

        assertThat(first.rowsCreated()).isEqualTo(2);
        assertThat(first.rowsSkipped()).isZero();

        assertThat(second.rowsSeen()).isEqualTo(2);
        assertThat(second.rowsCreated()).isZero();
        assertThat(second.rowsSkipped()).isEqualTo(2);
        assertThat(complaints.count()).isEqualTo(complaintsAfterFirst);
        assertThat(run(second).getStatus()).isEqualTo(IngestionStatus.SUCCESS);
    }

    @Test
    @DisplayName("uq_source_row, not the application lookup, is what makes that true")
    void theUniqueConstraintIsTheGuarantee() {
        String body =
                HEADERS + "\n2026/02/01 10:00:00,dup@example.com,Student,CE21B003,9000000003,305,Fan not working,\n";

        IngestionSummary summary = uploads.upload(csv(body));
        SourceRow ingested =
                sourceRows.findAll().stream()
                        .filter(row -> summary.runId() == row.getFirstSeenRunId())
                        .findFirst()
                        .orElseThrow();

        assertThat(ingested.getSourceType()).isEqualTo(SourceType.CSV_UPLOAD);
        assertThat(ingested.getSourceRef()).isEqualTo(CsvComplaintSource.SOURCE_REF);

        // Bypass the service entirely and insert the same identity straight at the repository.
        // If the only protection were the application's exists-check, this would succeed.
        SourceRow collidingInsert =
                new SourceRow(
                        ingested.getSourceType(),
                        ingested.getSourceRef(),
                        ingested.getResponseKey(),
                        ingested.getContentHash(),
                        99,
                        "{}",
                        ingested.getFirstSeenRunId(),
                        Instant.now());

        assertThatThrownBy(() -> sourceRows.saveAndFlush(collidingInsert))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("two residents submitting identical text are two complaints, not one")
    void identicalTextFromTwoResidentsIsTwoComplaints() {
        String body =
                HEADERS
                        + "\n"
                        + "2026/03/01 08:00:00,alpha@example.com,Student Alpha,CE21B010,9000000010,410,No water,RO\n"
                        + "2026/03/01 08:00:00,beta@example.com,Student Beta,CE21B011,9000000011,411,No water,RO\n";

        IngestionSummary summary = uploads.upload(csv(body));

        assertThat(summary.rowsCreated()).isEqualTo(2);
        assertThat(complaintsFrom(summary))
                .hasSize(2)
                .extracting(Complaint::getReporterRoom)
                .containsExactlyInAnyOrder("410", "411");
    }

    @Test
    @DisplayName("the same resident twice in one second is still two complaints — section 5.3's ordinal")
    void twoSubmissionsInTheSameSecondAreTwoComplaints() {
        String body =
                HEADERS
                        + "\n"
                        + "2026/03/02 08:00:00,same@example.com,Student,CE21B020,9000000020,510,Tap dripping,\n"
                        + "2026/03/02 08:00:00,same@example.com,Student,CE21B020,9000000020,510,Light flickering,\n";

        IngestionSummary first = uploads.upload(csv(body));
        IngestionSummary second = uploads.upload(csv(body));

        assertThat(first.rowsCreated()).isEqualTo(2);
        // And the ordinal is deterministic, so the re-upload still duplicates nothing.
        assertThat(second.rowsCreated()).isZero();
        assertThat(second.rowsSkipped()).isEqualTo(2);
    }

    // ---- normalization --------------------------------------------------

    @Test
    @DisplayName("an ingested complaint carries the resident's wording and details, classified and P2")
    void anIngestedComplaintIsShapedAsSection54Says() {
        String body =
                HEADERS
                        + "\n2026/04/01 07:45:00,carry@example.com,Student Carry,ce21b030,+91 90000 00030,Room 612,Ceiling leaking badly,Civil\n";

        IngestionSummary summary = uploads.upload(csv(body));
        Complaint complaint = complaintsFrom(summary).get(0);

        assertThat(complaint.getRawText()).isEqualTo("Ceiling leaking badly");
        assertThat(complaint.getReportedAt()).isEqualTo(Instant.parse("2026-04-01T02:15:00Z"));
        assertThat(complaint.getReporterRoom()).isEqualTo("612");
        assertThat(complaint.getReporterName()).isEqualTo("Student Carry");
        assertThat(complaint.getReporterRollNo()).isEqualTo("CE21B030");
        assertThat(complaint.getReporterPhone()).isEqualTo("+919000000030");
        assertThat(complaint.getReporterContact()).isEqualTo("carry@example.com");
        assertThat(complaint.getPublicRef()).matches("CAU-2026-\\d{4,}");

        // The weighted lexicon ran on the way in: `ceiling` and `leaking` are both Civil and
        // nothing else fired, so the confidence gate accepted it (section 5.5). No priority rules
        // exist yet, so P2 is still the flat default (PR 12).
        assertThat(complaint.getDomain()).isEqualTo(ComplaintDomain.CIVIL);
        assertThat(complaint.getClassifiedBy()).isEqualTo(ClassificationMethod.RULES);
        assertThat(complaint.getPriority()).isEqualTo(Priority.P2);
        assertThat(complaint.getStatus()).isEqualTo(ComplaintStatus.NEW);
    }

    @Test
    @DisplayName("the CSV path classifies too, and what the gate refuses arrives in triage")
    void theCsvPathIsClassifiedAndCanRouteToTriage() {
        String body =
                HEADERS
                        + "\n2026/04/03 07:45:00,triage@example.com,,,,410,water coming from ceiling fan,\n";

        Complaint complaint = complaintsFrom(uploads.upload(csv(body))).get(0);

        // Civil's `ceiling` and Electrical's `fan` score level, so the separation gate refuses to
        // pick one and the complaint reaches the manager instead of an attender on the wrong job.
        assertThat(complaint.getDomain()).isEqualTo(ComplaintDomain.UNCLASSIFIED);
        assertThat(complaint.getClassifiedBy()).isEqualTo(ClassificationMethod.FALLBACK);
        assertThat(complaint.getStatus()).isEqualTo(ComplaintStatus.TRIAGE);
    }

    @Test
    @DisplayName("the complaint and its source row point at each other")
    void theSourceRowAndComplaintAreLinked() {
        String body = HEADERS + "\n2026/04/02 07:45:00,link@example.com,,,,713,Window jammed,\n";

        IngestionSummary summary = uploads.upload(csv(body));
        SourceRow row =
                sourceRows.findAll().stream()
                        .filter(candidate -> summary.runId() == candidate.getFirstSeenRunId())
                        .findFirst()
                        .orElseThrow();
        Complaint complaint = complaints.findById(row.getComplaintId()).orElseThrow();

        assertThat(complaint.getSourceRowId()).isEqualTo(row.getId());
        assertThat(row.getComplaintId()).isEqualTo(complaint.getId());
    }

    @Test
    @DisplayName("a Form that does not ask for name, roll number or phone still ingests")
    void optionalColumnsAbsentStillIngests() {
        String body = "Timestamp,Describe the issue,Room Number\n2026/05/01 06:00:00,Bulb fused,808\n";

        IngestionSummary summary = uploads.upload(csv(body));
        Complaint complaint = complaintsFrom(summary).get(0);

        assertThat(summary.status()).isEqualTo(IngestionStatus.SUCCESS);
        assertThat(summary.rowsCreated()).isEqualTo(1);
        assertThat(complaint.getReporterName()).isNull();
        assertThat(complaint.getReporterRollNo()).isNull();
        assertThat(complaint.getReporterPhone()).isNull();
        assertThat(complaint.getReporterRoom()).isEqualTo("808");
    }

    // ---- failure behaviour ----------------------------------------------

    @Test
    @DisplayName("a missing required column fails the run with a clear reason and creates nothing")
    void aMissingRequiredColumnFailsTheRunAndCreatesNothing() {
        long complaintsBefore = complaints.count();
        long rowsBefore = sourceRows.count();

        IngestionSummary summary =
                uploads.upload(csv("Timestamp,Room Number\n2026/06/01 06:00:00,214\n"));

        assertThat(summary.failed()).isTrue();
        assertThat(summary.rowsCreated()).isZero();
        assertThat(summary.failureReason())
                .contains("description")
                .contains("Describe the issue");

        IngestionRun persisted = run(summary);
        assertThat(persisted.getStatus()).isEqualTo(IngestionStatus.FAILED);
        assertThat(persisted.getErrorDetail()).contains("description");
        assertThat(persisted.getFinishedAt()).isNotNull();

        assertThat(complaints.count()).isEqualTo(complaintsBefore);
        assertThat(sourceRows.count()).isEqualTo(rowsBefore);
    }

    @Test
    @DisplayName("a malformed row is skipped and recorded; the run succeeds and the good rows land")
    void aMalformedRowDoesNotTakeDownTheRun() {
        String body =
                HEADERS
                        + "\n"
                        + "2026/07/01 06:00:00,good1@example.com,Student,CE21B040,9000000040,901,Tap broken,\n"
                        + "last tuesday,bad@example.com,Student,CE21B041,9000000041,902,Unreadable date,\n"
                        + "2026/07/01 06:02:00,good2@example.com,Student,CE21B042,9000000042,903,Switch broken,\n";

        IngestionSummary summary = uploads.upload(csv(body));

        assertThat(summary.rowsSeen()).isEqualTo(3);
        assertThat(summary.rowsCreated()).isEqualTo(2);
        assertThat(summary.rowsSkipped()).isEqualTo(1);

        // Section 5.3: a malformed row is recorded on the run, which continues and succeeds.
        IngestionRun persisted = run(summary);
        assertThat(persisted.getStatus()).isEqualTo(IngestionStatus.SUCCESS);
        assertThat(persisted.getErrorDetail()).contains("row 3").contains("timestamp");

        // And nothing the resident wrote leaked into the stored reason (section 11.4).
        assertThat(persisted.getErrorDetail())
                .doesNotContain("CE21B041", "9000000041", "bad@example.com", "Unreadable date");
    }

    @Test
    @DisplayName("a row with a blank description is malformed, not an empty complaint")
    void aBlankDescriptionIsMalformed() {
        String body =
                HEADERS
                        + "\n"
                        + "2026/07/02 06:00:00,blank@example.com,Student,CE21B050,9000000050,904,   ,\n";

        IngestionSummary summary = uploads.upload(csv(body));

        assertThat(summary.rowsCreated()).isZero();
        assertThat(summary.rowsSkipped()).isEqualTo(1);
        assertThat(run(summary).getErrorDetail()).contains("description");
    }

    // ---- upload safety, section 11.6 -------------------------------------

    @Test
    @DisplayName("a non-CSV content type is refused before anything is parsed, and starts no run")
    void aNonCsvIsRefusedBeforeParsing() {
        long runsBefore = runs.count();

        assertThatThrownBy(
                        () ->
                                uploads.upload(
                                        new UploadedFile(
                                                "payload.pdf", "application/pdf", "%PDF-1.4".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(UploadRejectedException.class);

        assertThat(runs.count()).isEqualTo(runsBefore);
    }

    @Test
    @DisplayName("a file over 5 MB is refused on its length alone, and starts no run")
    void anOversizeFileIsRefusedBeforeParsing() {
        long runsBefore = runs.count();
        byte[] tooBig = new byte[(int) (5L * 1024 * 1024) + 1];

        assertThatThrownBy(() -> uploads.upload(new UploadedFile("responses.csv", "text/csv", tooBig)))
                .isInstanceOf(UploadRejectedException.class)
                .hasMessageContaining("5 MB");

        assertThat(runs.count()).isEqualTo(runsBefore);
    }

    @Test
    @DisplayName("a file over 5000 rows fails the run and ingests none of them")
    void tooManyRowsFailsTheRun() {
        long complaintsBefore = complaints.count();

        StringBuilder body = new StringBuilder("Timestamp,Describe the issue\n");
        for (int i = 0; i < 5001; i++) {
            body.append("2026/08/01 06:00:00,Row ").append(i).append('\n');
        }

        IngestionSummary summary = uploads.upload(csv(body.toString()));

        assertThat(summary.failed()).isTrue();
        assertThat(summary.failureReason()).contains("5000");
        assertThat(run(summary).getStatus()).isEqualTo(IngestionStatus.FAILED);
        assertThat(complaints.count()).isEqualTo(complaintsBefore);
    }

    // ---- authorization, section 11.3 -------------------------------------

    @Test
    @WithMockUser(roles = "ATTENDER")
    @DisplayName("an attender cannot upload — refused at the service, not merely hidden in the UI")
    void anAttenderCannotUpload() {
        long runsBefore = runs.count();

        assertThatThrownBy(
                        () -> uploads.upload(csv("Timestamp,Describe the issue\n2026/09/01 06:00:00,Nope\n")))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(runs.count()).isEqualTo(runsBefore);
    }
}
