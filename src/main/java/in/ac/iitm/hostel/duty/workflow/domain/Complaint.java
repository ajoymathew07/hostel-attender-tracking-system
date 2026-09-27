package in.ac.iitm.hostel.duty.workflow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * A resident's maintenance complaint — the central aggregate (data model section 3.5).
 *
 * <p>{@code rawText} keeps the resident's original wording verbatim. The classifier's output is
 * stored <em>alongside</em> it with a confidence score and the method used, never in place of it,
 * so a misclassification is always recoverable.
 *
 * <p>The docket lives in three columns here rather than a child table: corrections overwrite
 * {@code docketNo} and {@code audit_event} holds the old and new values (section 4.3, ADR-012).
 *
 * <p>{@code @Version} guards this aggregate against the lost update of C2 — a manager reassigning
 * while the attender resolves (section 7.7).
 */
@Entity
@Table(name = "complaint")
public class Complaint {

    /**
     * How long the assigned attender keeps seeing the student's name, roll number and phone
     * number after the complaint is resolved (section 11.4, NFR-9).
     *
     * <p>This is the retention rule, not a display preference, so it lives on the aggregate that
     * holds the data rather than in whichever screen happens to render it.
     */
    public static final Duration ATTENDER_CONTACT_WINDOW = Duration.ofDays(7);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Shown to humans, e.g. {@code CAU-2026-0417}. Unique via {@code uq_complaint_ref}. */
    @Column(name = "public_ref", nullable = false, length = 24)
    private String publicRef;

    @Column(name = "source_row_id")
    private Long sourceRowId;

    @Column(name = "raw_text", nullable = false, columnDefinition = "text")
    private String rawText;

    @Column(name = "reported_at", nullable = false)
    private Instant reportedAt;

    @Column(name = "reporter_room", length = 32)
    private String reporterRoom;

    /** Shown to the assigned attender, hidden 7 days after resolution (section 11.4). */
    @Column(name = "reporter_name", length = 128)
    private String reporterName;

    @Column(name = "reporter_roll_no", length = 32)
    private String reporterRollNo;

    @Column(name = "reporter_phone", length = 32)
    private String reporterPhone;

    /** Resident email; purged after 90 days (NFR-9, section 3.8). */
    @Column(name = "reporter_contact", length = 128)
    private String reporterContact;

    @Column(name = "floor_id")
    private Long floorId;

    @Column(name = "location_note", length = 255)
    private String locationNote;

    @Column(name = "docket_no", length = 64)
    private String docketNo;

    @Column(name = "docket_filed_at")
    private Instant docketFiledAt;

    @Column(name = "docket_filed_by")
    private Long docketFiledBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "domain", nullable = false, length = 16)
    private ComplaintDomain domain;

    /** 0.000 .. 1.000, guarded by {@code ck_conf}. */
    @Column(name = "domain_confidence", precision = 4, scale = 3)
    private BigDecimal domainConfidence;

    @Enumerated(EnumType.STRING)
    @Column(name = "classified_by", nullable = false, length = 16)
    private ClassificationMethod classifiedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 8)
    private Priority priority;

    /** Before aging, kept for explainability (section 4.5). */
    @Enumerated(EnumType.STRING)
    @Column(name = "base_priority", nullable = false, length = 8)
    private Priority basePriority;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private ComplaintStatus status;

    @Column(name = "sla_due_at")
    private Instant slaDueAt;

    @Column(name = "sla_breached", nullable = false)
    private boolean slaBreached;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    /** When this reached {@code RESOLVED}: the start of the attender's 7-day window (11.4). */
    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /** For JPA only. */
    protected Complaint() {}

    public Complaint(
            String publicRef,
            String rawText,
            Instant reportedAt,
            ComplaintDomain domain,
            ClassificationMethod classifiedBy,
            Priority basePriority,
            Instant createdAt) {
        this.publicRef = Objects.requireNonNull(publicRef, "publicRef");
        this.rawText = Objects.requireNonNull(rawText, "rawText");
        this.reportedAt = Objects.requireNonNull(reportedAt, "reportedAt");
        this.domain = Objects.requireNonNull(domain, "domain");
        this.classifiedBy = Objects.requireNonNull(classifiedBy, "classifiedBy");
        this.basePriority = Objects.requireNonNull(basePriority, "basePriority");
        this.priority = this.basePriority;
        this.status = ComplaintStatus.NEW;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = this.createdAt;
        this.slaBreached = false;
    }

    /**
     * The only mutator of {@code status}. In PR 11 this becomes reachable solely through
     * {@code WorkflowService.transition}, which owns the guarded transition table (section 4.1).
     */
    public void moveTo(ComplaintStatus newStatus, Instant at) {
        this.status = Objects.requireNonNull(newStatus, "status");
        this.updatedAt = Objects.requireNonNull(at, "at");
        if (newStatus == ComplaintStatus.RESOLVED) {
            this.resolvedAt = at;
        }
        if (newStatus == ComplaintStatus.CLOSED || newStatus == ComplaintStatus.REJECTED) {
            this.closedAt = at;
        }
    }

    /**
     * Whether an attender may still be shown the reporter's name, roll number and phone number
     * (section 11.4).
     *
     * <p>Scoping, not deletion: the fields stay on the record, and the manager's view of them is
     * unaffected, because oversight and bonus disputes outlive the work itself. An unresolved
     * complaint is live work, so the details are visible; after resolution they remain for
     * {@link #ATTENDER_CONTACT_WINDOW} and then stop.
     */
    public boolean reporterVisibleToAttender(Instant at) {
        Objects.requireNonNull(at, "at");
        return resolvedAt == null || at.isBefore(resolvedAt.plus(ATTENDER_CONTACT_WINDOW));
    }

    /** Transition T11 — the docket gate that {@code mark_resolved} depends on. */
    public void recordDocket(String newDocketNo, Long filedBy, Instant at) {
        this.docketNo = Objects.requireNonNull(newDocketNo, "docketNo");
        this.docketFiledBy = Objects.requireNonNull(filedBy, "filedBy");
        this.docketFiledAt = Objects.requireNonNull(at, "at");
        this.updatedAt = at;
    }

    /** You cannot collect points for a complaint you never filed with the Engineering Unit (T14). */
    public boolean hasDocket() {
        return docketNo != null && !docketNo.isBlank();
    }

    /** A manager setting the domain from triage (transition T4). */
    public void classifyManually(ComplaintDomain newDomain, Instant at) {
        this.domain = Objects.requireNonNull(newDomain, "domain");
        this.classifiedBy = ClassificationMethod.MANUAL;
        this.domainConfidence = null;
        this.updatedAt = Objects.requireNonNull(at, "at");
    }

    public void recordClassifierConfidence(BigDecimal confidence) {
        if (confidence != null
                && (confidence.signum() < 0 || confidence.compareTo(BigDecimal.ONE) > 0)) {
            throw new IllegalArgumentException("domainConfidence must lie in [0, 1]");
        }
        this.domainConfidence = confidence;
    }

    /** One step of priority aging; {@code basePriority} is deliberately left alone. */
    public void escalatePriority(Instant at) {
        this.priority = this.priority.escalated();
        this.updatedAt = Objects.requireNonNull(at, "at");
    }

    public void scheduleSla(Instant dueAt) {
        this.slaDueAt = dueAt;
    }

    public void markSlaBreached() {
        this.slaBreached = true;
    }

    public void locateAt(Long newFloorId, String newLocationNote) {
        this.floorId = newFloorId;
        this.locationNote = newLocationNote;
    }

    public void linkSourceRow(Long newSourceRowId) {
        this.sourceRowId = newSourceRowId;
    }

    public void recordReporter(
            String name, String rollNo, String phone, String contact, String room) {
        this.reporterName = name;
        this.reporterRollNo = rollNo;
        this.reporterPhone = phone;
        this.reporterContact = contact;
        this.reporterRoom = room;
    }

    /** NFR-9: the resident's email is nulled after 90 days; the audit records the purge. */
    public void purgeReporterContact() {
        this.reporterContact = null;
    }

    /** Raw text is replaced after a year; aggregates survive (section 3.8). */
    public void purgeRawText() {
        this.rawText = "[purged]";
    }

    public Long getId() {
        return id;
    }

    public String getPublicRef() {
        return publicRef;
    }

    public Long getSourceRowId() {
        return sourceRowId;
    }

    public String getRawText() {
        return rawText;
    }

    public Instant getReportedAt() {
        return reportedAt;
    }

    public String getReporterRoom() {
        return reporterRoom;
    }

    public String getReporterName() {
        return reporterName;
    }

    public String getReporterRollNo() {
        return reporterRollNo;
    }

    public String getReporterPhone() {
        return reporterPhone;
    }

    public String getReporterContact() {
        return reporterContact;
    }

    public Long getFloorId() {
        return floorId;
    }

    public String getLocationNote() {
        return locationNote;
    }

    public String getDocketNo() {
        return docketNo;
    }

    public Instant getDocketFiledAt() {
        return docketFiledAt;
    }

    public Long getDocketFiledBy() {
        return docketFiledBy;
    }

    public ComplaintDomain getDomain() {
        return domain;
    }

    public BigDecimal getDomainConfidence() {
        return domainConfidence;
    }

    public ClassificationMethod getClassifiedBy() {
        return classifiedBy;
    }

    public Priority getPriority() {
        return priority;
    }

    public Priority getBasePriority() {
        return basePriority;
    }

    public ComplaintStatus getStatus() {
        return status;
    }

    public Instant getSlaDueAt() {
        return slaDueAt;
    }

    public boolean isSlaBreached() {
        return slaBreached;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public long getVersion() {
        return version;
    }
}
