package in.ac.iitm.hostel.duty.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import in.ac.iitm.hostel.duty.ingestion.application.IngestionSummary;
import in.ac.iitm.hostel.duty.ingestion.application.SheetsIngestionService;
import in.ac.iitm.hostel.duty.ingestion.domain.IngestionRun;
import in.ac.iitm.hostel.duty.ingestion.domain.IngestionStatus;
import in.ac.iitm.hostel.duty.ingestion.domain.SourceRow;
import in.ac.iitm.hostel.duty.ingestion.domain.SourceType;
import in.ac.iitm.hostel.duty.ingestion.domain.TriggerType;
import in.ac.iitm.hostel.duty.ingestion.infrastructure.IngestionRunRepository;
import in.ac.iitm.hostel.duty.ingestion.infrastructure.SheetReader;
import in.ac.iitm.hostel.duty.ingestion.infrastructure.SheetsPollScheduler;
import in.ac.iitm.hostel.duty.ingestion.infrastructure.SheetsUnavailableException;
import in.ac.iitm.hostel.duty.ingestion.infrastructure.SourceRowRepository;
import in.ac.iitm.hostel.duty.support.AbstractIntegrationTest;
import in.ac.iitm.hostel.duty.workflow.domain.ClassificationMethod;
import in.ac.iitm.hostel.duty.workflow.domain.Complaint;
import in.ac.iitm.hostel.duty.workflow.domain.ComplaintDomain;
import in.ac.iitm.hostel.duty.workflow.domain.ComplaintStatus;
import in.ac.iitm.hostel.duty.workflow.domain.Priority;
import in.ac.iitm.hostel.duty.workflow.infrastructure.ComplaintRepository;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;

/**
 * Sheets ingestion end to end, against real PostgreSQL (test plan section 12.1).
 *
 * <p><strong>The real Sheets API is never called.</strong> {@link SheetReader} is stubbed with the
 * cell grid the API would have returned: the design says outright that what we test is our
 * reconciliation and failure handling, not Google's delivery, and a test that reached the real API
 * would need a credential in the repository — which section 11.5 forbids.
 *
 * <p>The headline property is NFR-1's: <strong>a complaint is never created twice</strong>. The
 * sheet is polled every three minutes over rows we mostly already hold, so the second pass being a
 * no-op is not an optimisation, it is the guarantee.
 *
 * <p>Student details in these fixtures are synthetic (section 11.4).
 */
@SpringBootTest(
        properties = {
            "ingestion.sheets.spreadsheet-id=1SyntheticResponseSheetIdForTests",
            // Non-blank so the service counts itself configured. It is never decoded, because the
            // reader is stubbed — there is no credential anywhere in this repository (section 11.5).
            "ingestion.sheets.credentials=stubbed-in-tests",
            // Long enough that the background poll cannot fire mid-test; every poll here is
            // called explicitly.
            "ingestion.sheets.poll-interval=PT1H"
        })
@WithMockUser(roles = "MANAGER")
class SheetsIngestionIntegrationTest extends AbstractIntegrationTest {

    private static final String SHEET_ID = "1SyntheticResponseSheetIdForTests";

    private static final String LOCK_NAME = "sheetsPoll";

    private static final List<Object> HEADERS =
            List.of(
                    "Timestamp",
                    "Email Address",
                    "Name",
                    "Roll Number",
                    "Phone Number",
                    "Room Number",
                    "Describe the issue",
                    "Type of issue");

    @MockBean private SheetReader reader;

    @Autowired private SheetsIngestionService sheets;
    @Autowired private SheetsPollScheduler scheduler;
    @Autowired private ComplaintRepository complaints;
    @Autowired private SourceRowRepository sourceRows;
    @Autowired private IngestionRunRepository runs;
    @Autowired private JdbcTemplate jdbc;

    private static List<Object> row(Object... cells) {
        return List.of(cells);
    }

    /** Stubs the API with a header row followed by these responses. */
    private void sheetHolds(List<Object>... responses) {
        List<List<Object>> grid = new ArrayList<>();
        grid.add(HEADERS);
        grid.addAll(List.of(responses));
        given(reader.readValues(anyString(), anyString())).willReturn(grid);
    }

    private IngestionSummary sync() {
        return sheets.syncNow().orElseThrow();
    }

    private IngestionRun run(IngestionSummary summary) {
        return runs.findById(summary.runId()).orElseThrow();
    }

    private List<Complaint> complaintsFrom(IngestionSummary summary) {
        return sourceRows.findAll().stream()
                .filter(sourceRow -> summary.runId() == sourceRow.getFirstSeenRunId())
                .map(SourceRow::getComplaintId)
                .filter(Objects::nonNull)
                .map(id -> complaints.findById(id).orElseThrow())
                .toList();
    }

    // ---- idempotency, the headline property ------------------------------

    @Test
    @DisplayName("polling the same sheet twice creates no duplicate complaints")
    void pollingTwiceCreatesNoDuplicates() {
        sheetHolds(
                row("2026/02/01 09:12:00", "one@example.com", "Student One", "CE21B001", "9000000001", "214", "No water since morning", "RO"),
                row("2026/02/01 09:30:00", "two@example.com", "Student Two", "CE21B002", "9000000002", "118", "Door latch broken", "Civil"));

        IngestionSummary first = sync();
        long complaintsAfterFirst = complaints.count();

        IngestionSummary second = sync();

        assertThat(first.rowsCreated()).isEqualTo(2);
        assertThat(first.rowsSkipped()).isZero();

        assertThat(second.rowsSeen()).isEqualTo(2);
        assertThat(second.rowsCreated()).isZero();
        assertThat(second.rowsSkipped()).isEqualTo(2);
        assertThat(complaints.count()).isEqualTo(complaintsAfterFirst);
        assertThat(run(second).getStatus()).isEqualTo(IngestionStatus.SUCCESS);
    }

    @Test
    @DisplayName("a row edited in the sheet collides on response_key rather than becoming a second complaint")
    void anEditedRowDoesNotBecomeASecondComplaint() {
        sheetHolds(
                row("2026/02/05 11:00:00", "edit@example.com", "Student Edit", "CE21B003", "9000000003", "305", "Fan not working", ""));
        IngestionSummary first = sync();
        Complaint created = complaintsFrom(first).get(0);

        // Same submission, same submitter, different answer — "edit response" on the Form. Section
        // 5.3 defers amendments to Phase 5: the row is skipped and the complaint keeps its
        // original text, which is the safe behaviour.
        sheetHolds(
                row("2026/02/05 11:00:00", "edit@example.com", "Student Edit", "CE21B003", "9000000003", "305", "Fan not working AND light broken", ""));
        IngestionSummary second = sync();

        assertThat(second.rowsCreated()).isZero();
        assertThat(second.rowsSkipped()).isEqualTo(1);
        assertThat(complaintsFrom(second)).isEmpty();
        assertThat(complaints.findById(created.getId()).orElseThrow().getRawText())
                .isEqualTo("Fan not working");
    }

    @Test
    @DisplayName("the sheet id is the source ref, which is what makes uq_source_row bite here")
    void theSheetIdIsTheSourceRef() {
        sheetHolds(
                row("2026/02/06 12:00:00", "ref@example.com", "Student Ref", "CE21B004", "9000000004", "410", "Tap dripping", ""));

        IngestionSummary summary = sync();
        SourceRow ingested =
                sourceRows.findAll().stream()
                        .filter(sourceRow -> summary.runId() == sourceRow.getFirstSeenRunId())
                        .findFirst()
                        .orElseThrow();

        assertThat(ingested.getSourceType()).isEqualTo(SourceType.GOOGLE_SHEETS);
        assertThat(ingested.getSourceRef()).isEqualTo(SHEET_ID);
    }

    // ---- normalization, shared with the CSV path -------------------------

    @Test
    @DisplayName("a sheet row becomes a complaint with exactly the normalization the CSV path gives")
    void aSheetRowIsNormalizedLikeAnUploadedRow() {
        sheetHolds(
                row("2026/04/01 07:45:00", "carry@example.com", "Student Carry", "ce21b030", "+91 90000 00030", "Room 612", "Ceiling leaking badly", "Civil"));

        IngestionSummary summary = sync();
        Complaint complaint = complaintsFrom(summary).get(0);

        assertThat(complaint.getRawText()).isEqualTo("Ceiling leaking badly");
        assertThat(complaint.getReportedAt()).isEqualTo(Instant.parse("2026-04-01T02:15:00Z"));
        assertThat(complaint.getReporterRoom()).isEqualTo("612");
        assertThat(complaint.getReporterName()).isEqualTo("Student Carry");
        assertThat(complaint.getReporterRollNo()).isEqualTo("CE21B030");
        assertThat(complaint.getReporterPhone()).isEqualTo("+919000000030");
        assertThat(complaint.getReporterContact()).isEqualTo("carry@example.com");
        assertThat(complaint.getPublicRef()).matches("CAU-2026-\\d{4,}");

        assertThat(complaint.getDomain()).isEqualTo(ComplaintDomain.UNCLASSIFIED);
        assertThat(complaint.getClassifiedBy()).isEqualTo(ClassificationMethod.FALLBACK);
        assertThat(complaint.getPriority()).isEqualTo(Priority.P2);
        assertThat(complaint.getStatus()).isEqualTo(ComplaintStatus.NEW);
    }

    // ---- the watermark, section 5.3 --------------------------------------

    @Test
    @DisplayName("the watermark, minus the 50-row overlap, is what the next run considers")
    void theWatermarkNarrowsTheNextRun() {
        sheetHolds(
                row("2026/05/01 06:00:00", "mark@example.com", "Student Mark", "CE21B060", "9000000060", "808", "Bulb fused", ""));
        IngestionSummary first = sync();

        // Pretend ingestion has already reached row 200 of this sheet. The next run's window is
        // then max(1, 200 − 50) = 150, so the rows the stub returns — at 2 and below — are never
        // considered.
        SourceRow watermark =
                sourceRows.saveAndFlush(
                        new SourceRow(
                                SourceType.GOOGLE_SHEETS,
                                SHEET_ID,
                                "f".repeat(64),
                                "e".repeat(64),
                                200,
                                "{}",
                                first.runId(),
                                Instant.now()));
        try {
            IngestionSummary second = sync();

            assertThat(second.rowsSeen()).isZero();
            assertThat(second.rowsCreated()).isZero();
            assertThat(run(second).getStatus()).isEqualTo(IngestionStatus.SUCCESS);
        } finally {
            // The watermark is global to the sheet, so it must not outlive this test.
            sourceRows.delete(watermark);
        }
    }

    // ---- failure behaviour -----------------------------------------------

    @Test
    @DisplayName("a renamed required column fails the run loudly and creates nothing")
    void aMissingRequiredColumnFailsTheRunAndCreatesNothing() {
        long complaintsBefore = complaints.count();
        long rowsBefore = sourceRows.count();

        given(reader.readValues(anyString(), anyString()))
                .willReturn(List.of(row("Timestamp", "What is wrong"), row("2026/06/01 06:00:00", "No water")));

        IngestionSummary summary = sync();

        assertThat(summary.failed()).isTrue();
        assertThat(summary.rowsCreated()).isZero();
        assertThat(summary.failureReason()).contains("description").contains("Describe the issue");

        IngestionRun persisted = run(summary);
        assertThat(persisted.getStatus()).isEqualTo(IngestionStatus.FAILED);
        assertThat(persisted.getFinishedAt()).isNotNull();

        assertThat(complaints.count()).isEqualTo(complaintsBefore);
        assertThat(sourceRows.count()).isEqualTo(rowsBefore);
    }

    @Test
    @DisplayName("a sheet that cannot be read fails the run with a reason, rather than failing silently")
    void anUnreachableSheetFailsTheRun() {
        given(reader.readValues(anyString(), anyString()))
                .willThrow(new SheetsUnavailableException(SHEET_ID, new IOException("connection reset")));

        IngestionSummary summary = sync();

        // Silence is the failure mode section 5.3 names; the run row is how the manager finds out.
        assertThat(summary.failed()).isTrue();
        assertThat(run(summary).getStatus()).isEqualTo(IngestionStatus.FAILED);
        assertThat(run(summary).getErrorDetail()).contains(SHEET_ID).contains("IOException");
    }

    // ---- the scheduled poll and its lock, section 2.5 --------------------

    @Test
    @DisplayName("the scheduled poll takes the database lock, and records itself as SCHEDULED")
    void theScheduledPollTakesTheLock() {
        sheetHolds(
                row("2026/07/01 06:00:00", "poll@example.com", "Student Poll", "CE21B070", "9000000070", "901", "Switch broken", ""));

        scheduler.poll();

        assertThat(lockHolder()).isNotBlank();
        assertThat(runs.findTop20ByOrderByStartedAtDesc())
                .extracting(IngestionRun::getTriggerType)
                .contains(TriggerType.SCHEDULED);
    }

    @Test
    @DisplayName("a poll cannot run while another instance holds the lock — no double ingestion")
    void aPollHeldByAnotherInstanceDoesNotRun() {
        sheetHolds(
                row("2026/07/02 06:00:00", "locked@example.com", "Student Locked", "CE21B071", "9000000071", "902", "Geyser dead", ""));

        try {
            // Take the lock once, then hold it into the future as a second instance would.
            scheduler.poll();
            jdbc.update(
                    "UPDATE shedlock SET lock_until = now() + interval '1 hour', locked_at = now(),"
                            + " locked_by = 'another-instance' WHERE name = ?",
                    LOCK_NAME);

            long runsBefore = runs.count();
            scheduler.poll();

            assertThat(runs.count()).isEqualTo(runsBefore);
        } finally {
            // Hand the lock back rather than deleting the row: ShedLock remembers that the row
            // exists and only ever updates it again, so removing it would wedge every later poll.
            jdbc.update("UPDATE shedlock SET lock_until = now() WHERE name = ?", LOCK_NAME);
        }
    }

    private String lockHolder() {
        return jdbc.queryForObject("SELECT locked_by FROM shedlock WHERE name = ?", String.class, LOCK_NAME);
    }

    // ---- configuration ----------------------------------------------------

    @Test
    @DisplayName("a configured instance reports itself configured, and Sync Now returns a run")
    void aConfiguredInstanceSyncs() {
        sheetHolds();

        assertThat(sheets.configured()).isTrue();
        Optional<IngestionSummary> summary = sheets.syncNow();

        assertThat(summary).isPresent();
        assertThat(summary.orElseThrow().rowsSeen()).isZero();
    }
}
