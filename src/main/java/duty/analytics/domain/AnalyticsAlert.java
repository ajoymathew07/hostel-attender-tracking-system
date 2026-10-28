package duty.analytics.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;

/**
 * One finding of an anomaly detector, with the evidence a sceptical manager needs to judge it
 * (section 8.3). The same finding raised again updates this row, through {@code dedupe_key}.
 */
@Entity
@Table(name = "analytics_alert")
public class AnalyticsAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "detector", nullable = false, length = 8)
    private String detector;

    @Column(name = "severity", nullable = false, length = 8)
    private String severity;

    @Column(name = "subject_type", nullable = false, length = 24)
    private String subjectType;

    @Column(name = "subject_ref", nullable = false, length = 64)
    private String subjectRef;

    @Column(name = "summary", nullable = false, length = 255)
    private String summary;

    @Column(name = "evidence", nullable = false, columnDefinition = "text")
    private String evidence;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @Column(name = "acknowledged_by")
    private Long acknowledgedBy;

    @Column(name = "dedupe_key", nullable = false, length = 128)
    private String dedupeKey;

    /** For JPA only. */
    protected AnalyticsAlert() {}

    public AnalyticsAlert(
            String detector,
            String severity,
            String subjectType,
            String subjectRef,
            String summary,
            String evidence,
            Instant detectedAt,
            String dedupeKey) {
        this.detector = Objects.requireNonNull(detector, "detector");
        this.subjectType = Objects.requireNonNull(subjectType, "subjectType");
        this.subjectRef = Objects.requireNonNull(subjectRef, "subjectRef");
        this.dedupeKey = Objects.requireNonNull(dedupeKey, "dedupeKey");
        refresh(severity, summary, evidence, detectedAt);
    }

    /** The same finding seen again: newer figures, same row. An acknowledgement stands. */
    public void refresh(String severity, String summary, String evidence, Instant detectedAt) {
        this.severity = Objects.requireNonNull(severity, "severity");
        this.summary = Objects.requireNonNull(summary, "summary");
        this.evidence = Objects.requireNonNull(evidence, "evidence");
        this.detectedAt = Objects.requireNonNull(detectedAt, "detectedAt");
    }

    public void acknowledge(Long by, Instant at) {
        this.acknowledgedBy = by;
        this.acknowledgedAt = Objects.requireNonNull(at, "at");
    }

    public Long getId() {
        return id;
    }

    public String getDetector() {
        return detector;
    }

    public String getSeverity() {
        return severity;
    }

    public String getSubjectType() {
        return subjectType;
    }

    public String getSubjectRef() {
        return subjectRef;
    }

    public String getSummary() {
        return summary;
    }

    public String getEvidence() {
        return evidence;
    }

    public Instant getDetectedAt() {
        return detectedAt;
    }

    public Instant getAcknowledgedAt() {
        return acknowledgedAt;
    }

    public String getDedupeKey() {
        return dedupeKey;
    }
}
