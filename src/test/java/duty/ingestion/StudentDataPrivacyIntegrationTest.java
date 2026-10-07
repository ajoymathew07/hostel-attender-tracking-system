package duty.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import duty.audit.domain.AuditEvent;
import duty.audit.infrastructure.AuditEventRepository;
import duty.ingestion.application.CsvUploadService.UploadedFile;
import duty.ingestion.application.CsvUploadService;
import duty.ingestion.application.IngestionPipeline.IngestionSummary;
import duty.support.AbstractIntegrationTest;
import duty.workflow.application.ComplaintQueryService.ComplaintListItem;
import duty.workflow.application.ComplaintQueryService;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;

/**
 * Student name, roll number and phone never appear in the logs. Watches the real log output during an
 * upload.
 *
 * <p>All values here are made up.
 */
@SpringBootTest
@WithMockUser(roles = "MANAGER")
class StudentDataPrivacyIntegrationTest extends AbstractIntegrationTest {

    private static final String NAME = "Synthetic Resident";
    private static final String ROLL_NO = "CE21B777";
    private static final String PHONE = "9000000777";
    private static final String EMAIL = "synthetic.resident@example.com";

    private static final String HEADERS =
            "Timestamp,Email Address,Name,Roll Number,Phone Number,Room Number,Describe the issue";

    /**
     * A CSV with its own timestamp per test, so tests sharing the database do not skip each other's row.
     */
    private static String csvAt(String submittedAt) {
        return HEADERS
                + "\n"
                + submittedAt
                + ","
                + EMAIL
                + ","
                + NAME
                + ","
                + ROLL_NO
                + ","
                + PHONE
                + ",1501,Geyser not heating\n"
                // A second, malformed row: the error path is where a careless message would leak.
                + "not a date,"
                + EMAIL
                + ","
                + NAME
                + ","
                + ROLL_NO
                + ","
                + PHONE
                + ",1502,Geyser not heating either\n";
    }

    @Autowired private CsvUploadService uploads;
    @Autowired private ComplaintQueryService query;
    @Autowired private AuditEventRepository auditEvents;

    private Logger applicationLogger;
    private ListAppender<ILoggingEvent> captured;

    @BeforeEach
    void captureLogs() {
        applicationLogger = (Logger) LoggerFactory.getLogger("duty");
        applicationLogger.setLevel(Level.TRACE);
        captured = new ListAppender<>();
        captured.start();
        applicationLogger.addAppender(captured);
    }

    @AfterEach
    void releaseLogs() {
        applicationLogger.detachAppender(captured);
        captured.stop();
    }

    private static UploadedFile csv(String submittedAt) {
        return new UploadedFile(
                "responses.csv", "text/csv", csvAt(submittedAt).getBytes(StandardCharsets.UTF_8));
    }

    private String allLogOutput() {
        StringBuilder output = new StringBuilder();
        for (ILoggingEvent event : captured.list) {
            output.append(event.getFormattedMessage()).append('\n');
            for (Object argument : event.getArgumentArray() == null ? new Object[0] : event.getArgumentArray()) {
                output.append(String.valueOf(argument)).append('\n');
            }
            if (event.getThrowableProxy() != null) {
                output.append(event.getThrowableProxy().getMessage()).append('\n');
            }
        }
        return output.toString();
    }

    @Test
    @DisplayName("ingesting a resident's details writes none of them to a log, on the good path or the bad")
    void studentDetailsNeverReachTheLog() {
        IngestionSummary summary = uploads.upload(csv("2026/02/20 09:01:00"));

        assertThat(summary.rowsCreated()).isEqualTo(1);
        assertThat(summary.rowsSkipped()).isEqualTo(1);
        assertThat(captured.list).as("the run logged something, so the assertion is not vacuous").isNotEmpty();

        assertThat(allLogOutput())
                .doesNotContain(NAME)
                .doesNotContain(ROLL_NO)
                .doesNotContain(PHONE)
                .doesNotContain(EMAIL)
                .doesNotContain("Geyser not heating");
    }

    @Test
    @DisplayName("nor does the run's recorded failure reason, which the manager sees on screen")
    void theFailureReasonNamesTheRowAndNothingElse() {
        IngestionSummary summary = uploads.upload(csv("2026/02/20 09:02:00"));

        assertThat(summary.failureReason())
                .contains("row 3")
                .doesNotContain(NAME, ROLL_NO, PHONE, EMAIL, "Geyser");
    }

    @Test
    @DisplayName("nor does the audit trail, which is retained indefinitely")
    void theAuditTrailCarriesNoStudentData() {
        IngestionSummary summary = uploads.upload(csv("2026/02/20 09:03:00"));
        long complaintId =
                query.listAll().stream()
                        .filter(item -> "1501".equals(item.reporterRoom()))
                        .findFirst()
                        .orElseThrow()
                        .id();

        List<AuditEvent> events = new ArrayList<>();
        events.addAll(auditEvents.findByEntityTypeAndEntityIdOrderByOccurredAtAsc("complaint", complaintId));
        events.addAll(auditEvents.findByEntityTypeAndEntityIdOrderByOccurredAtAsc("ingestion_run", summary.runId()));

        assertThat(events).as("the upload audited something").isNotEmpty();
        String audit =
                events.stream()
                        .map(event -> event.getEventType() + " " + event.getDetail())
                        .reduce("", (a, b) -> a + "\n" + b);

        assertThat(audit).doesNotContain(NAME, ROLL_NO, PHONE, EMAIL, "Geyser");
    }

    @Test
    @DisplayName("but the manager does see them — they are stored, not suppressed")
    void theManagerStillSeesTheResidentsDetails() {
        uploads.upload(csv("2026/02/20 09:04:00"));

        List<ComplaintListItem> listed = query.listAll();

        assertThat(listed)
                .anySatisfy(
                        item -> {
                            assertThat(item.reporterName()).isEqualTo(NAME);
                            assertThat(item.reporterRollNo()).isEqualTo(ROLL_NO);
                            assertThat(item.reporterPhone()).isEqualTo(PHONE);
                            assertThat(item.reporterRoom()).isEqualTo("1501");
                        });
    }

    @Test
    @DisplayName("a list item's toString carries none of it either — section 11.4")
    void listItemToStringIsSafe() {
        uploads.upload(csv("2026/02/20 09:05:00"));

        assertThat(query.listAll())
                .allSatisfy(
                        item ->
                                assertThat(item.toString())
                                        .doesNotContain(NAME, ROLL_NO, PHONE, EMAIL, "Geyser"));
    }

    @Test
    @WithMockUser(roles = "ATTENDER")
    @DisplayName("an attender cannot read the manager's complaint list at the service layer")
    void anAttenderCannotListEveryComplaint() {
        assertThatThrownBy(() -> query.listAll()).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> query.count()).isInstanceOf(AccessDeniedException.class);
    }
}
