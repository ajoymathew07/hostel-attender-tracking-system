package duty.workflow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * A resident's maintenance complaint — the central aggregate (data model section 3.5).
 *
 * <p>{@code rawText} keeps the resident's original wording verbatim. The domain is stored
 * <em>alongside</em> it with the method that set it, never in place of it, so a complaint filed
 * under the wrong heading is always recoverable.
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

    /** When the SLA clock was paused by T12; {@code null} while it is running (section 4.5). */
    @Column(name = "hold_started_at")
    private Instant holdStartedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    /** When this reached {@code RESOLVED}: the start of the attender's 7-day window (11.4). */
    @Column(name = "resolved_at")
    private Instant resolvedAt;

    /** Transition T7's one side effect. */
    @Column(name = "inspection_started_at")
    private Instant inspectionStartedAt;

    /**
     * How many times a manager has reopened this (T17) — the {@code n} of the resolve credit's
     * idempotency key, {@code RESOLVE:{task}:{attender}:r{n}} (section 7.3).
     *
     * <p>A counter of an event, not a balance. Section 7.3 forbids a stored <em>points</em> total
     * because a balance is a SUM over immutable facts; this is a fact about the complaint, and the
     * key that keeps re-credits honest is derived from it.
     */
    @Column(name = "reopen_count", nullable = false)
    private int reopenCount;

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
     * The only mutator of {@code status}, and reached solely through
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

    /** Transition T7 — the attender is on site. */
    public void startInspection(Instant at) {
        this.inspectionStartedAt = Objects.requireNonNull(at, "at");
        this.updatedAt = at;
    }

    /**
     * Transition T17 — the work was not done after all.
     *
     * <p>Clearing {@code resolvedAt} is not bookkeeping tidiness. It is what stops the 7-day PII
     * window (section 11.4) from expiring while the attender is back on live work, and what stops
     * the auto-close clock (T16) running on a complaint that is no longer resolved.
     */
    public void reopen(Instant at) {
        this.resolvedAt = null;
        this.reopenCount++;
        this.updatedAt = Objects.requireNonNull(at, "at");
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
        this.updatedAt = Objects.requireNonNull(at, "at");
    }

    /** One step of priority aging; {@code basePriority} is deliberately left alone. */
    public void escalatePriority(Instant at) {
        this.priority = this.priority.escalated();
        this.updatedAt = Objects.requireNonNull(at, "at");
    }

    /**
     * A manager's downgrade — section 4.5's third row, the one source of {@link Priority#P3}.
     *
     * <p>This moves {@code basePriority} as well as {@code priority}, because it is a judgement
     * about the work itself rather than an effect of the clock: the aging job must be able to raise
     * it again from P3, and the dashboard must not read the result as "aged from P2".
     */
    public void downgrade(Instant at) {
        this.basePriority = Priority.P3;
        this.priority = Priority.P3;
        this.updatedAt = Objects.requireNonNull(at, "at");
    }

    /** Whether the current priority was reached by aging rather than assigned (section 4.5). */
    public boolean aged() {
        return priority != basePriority;
    }

    public void scheduleSla(Instant dueAt) {
        this.slaDueAt = dueAt;
    }

    /** T12 — the SLA clock stops while the work is blocked. */
    public void pauseSlaClock(Instant at) {
        this.holdStartedAt = Objects.requireNonNull(at, "at");
    }

    /**
     * T13 — the clock restarts, and the due date moves by however long the hold lasted.
     *
     * <p>Wall-clock elapsed time, which is what section 4.5 says: "extend due date by hold
     * duration". A complaint held for a day is due a day later, whatever part of that day was
     * inside working hours — the attender was not able to act during any of it.
     */
    public void resumeSlaClock(Instant at) {
        Objects.requireNonNull(at, "at");
        if (holdStartedAt != null && slaDueAt != null) {
            this.slaDueAt = slaDueAt.plus(Duration.between(holdStartedAt, at));
        }
        this.holdStartedAt = null;
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

    public Instant getHoldStartedAt() {
        return holdStartedAt;
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

    public Instant getInspectionStartedAt() {
        return inspectionStartedAt;
    }

    public int getReopenCount() {
        return reopenCount;
    }

    public long getVersion() {
        return version;
    }
}
