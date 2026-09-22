package in.ac.iitm.hostel.duty.ingestion.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;

/**
 * One execution of the ingestion pipeline, and its tally (data model section 3.5).
 *
 * <p>A run is also the unit a {@code correlation_id} traces: every audit row written while this run
 * is in flight carries the same correlation, so "what did the 06:03 poll actually do" is one query.
 */
@Entity
@Table(name = "ingestion_run")
public class IngestionRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 16)
    private SourceType sourceType;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 16)
    private TriggerType triggerType;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "rows_seen", nullable = false)
    private int rowsSeen;

    @Column(name = "rows_created", nullable = false)
    private int rowsCreated;

    @Column(name = "rows_skipped", nullable = false)
    private int rowsSkipped;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private IngestionStatus status;

    @Column(name = "error_detail", length = 2000)
    private String errorDetail;

    @Column(name = "triggered_by")
    private Long triggeredBy;

    /** For JPA only. */
    protected IngestionRun() {}

    public IngestionRun(
            SourceType sourceType, TriggerType triggerType, Instant startedAt, Long triggeredBy) {
        this.sourceType = Objects.requireNonNull(sourceType, "sourceType");
        this.triggerType = Objects.requireNonNull(triggerType, "triggerType");
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
        this.triggeredBy = triggeredBy;
        this.status = IngestionStatus.RUNNING;
    }

    public void countRow(boolean created) {
        this.rowsSeen++;
        if (created) {
            this.rowsCreated++;
        } else {
            this.rowsSkipped++;
        }
    }

    public void succeed(Instant at) {
        this.status = IngestionStatus.SUCCESS;
        this.finishedAt = Objects.requireNonNull(at, "at");
    }

    /** A run fails loudly — a renamed required column must not pass silently (test plan E12). */
    public void fail(Instant at, String detail) {
        this.status = IngestionStatus.FAILED;
        this.finishedAt = Objects.requireNonNull(at, "at");
        this.errorDetail = detail;
    }

    public boolean isFinished() {
        return status != IngestionStatus.RUNNING;
    }

    public Long getId() {
        return id;
    }

    public SourceType getSourceType() {
        return sourceType;
    }

    public TriggerType getTriggerType() {
        return triggerType;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public int getRowsSeen() {
        return rowsSeen;
    }

    public int getRowsCreated() {
        return rowsCreated;
    }

    public int getRowsSkipped() {
        return rowsSkipped;
    }

    public IngestionStatus getStatus() {
        return status;
    }

    public String getErrorDetail() {
        return errorDetail;
    }

    public Long getTriggeredBy() {
        return triggeredBy;
    }
}
