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
import java.util.List;
import java.util.Objects;

/**
 * A resident's maintenance complaint: the central entity.
 *
 * <p>{@code rawText} keeps the resident's wording unchanged; the domain is stored beside it. The docket
 * is stored in columns here, with corrections kept in the audit log. {@code @Version} stops a
 * reassignment and a resolve overwriting each other.
 */
@Entity
@Table(name = "complaint")
public class Complaint {

    /**
     * How long after resolution the attender may still see the student's name, roll number and phone.
     */
    public static final Duration ATTENDER_CONTACT_WINDOW = Duration.ofDays(7);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The human-facing reference, e.g. {@code CAU-2026-0417}. */
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

    /** Shown to the assigned attender until 7 days after resolution. */
    @Column(name = "reporter_name", length = 128)
    private String reporterName;

    @Column(name = "reporter_roll_no", length = 32)
    private String reporterRollNo;

    @Column(name = "reporter_phone", length = 32)
    private String reporterPhone;

    /** Resident email; purged after 90 days. */
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

    /** The priority before ageing, so the list can show "aged from". */
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

    /** When the SLA clock was paused (T12); {@code null} while running. */
    @Column(name = "hold_started_at")
    private Instant holdStartedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    /** When it was resolved: starts the attender's 7-day window. */
    @Column(name = "resolved_at")
    private Instant resolvedAt;

    /** When the attender started work (T7). */
    @Column(name = "inspection_started_at")
    private Instant inspectionStartedAt;

    /**
     * How many times a manager has reopened it (T17). Part of the resolve credit's key, so a re-resolution
     * gets credited again.
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

    /** Sets the status. Only {@code WorkflowService.transition} calls this. */
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
     * Whether an attender may still see the student's details: always while unresolved, then for 7 days.
     * The manager always can.
     */
    public boolean reporterVisibleToAttender(Instant at) {
        Objects.requireNonNull(at, "at");
        return resolvedAt == null || at.isBefore(resolvedAt.plus(ATTENDER_CONTACT_WINDOW));
    }

    /** The attender started work (T7). */
    public void startInspection(Instant at) {
        this.inspectionStartedAt = Objects.requireNonNull(at, "at");
        this.updatedAt = at;
    }

    /**
     * Reopens the complaint (T17). Clears {@code resolvedAt} so the 7-day window and the auto-close clock
     * stop.
     */
    public void reopen(Instant at) {
        this.resolvedAt = null;
        this.reopenCount++;
        this.updatedAt = Objects.requireNonNull(at, "at");
    }

    /** Records the docket number (T11), which resolving requires. */
    public void recordDocket(String newDocketNo, Long filedBy, Instant at) {
        this.docketNo = Objects.requireNonNull(newDocketNo, "docketNo");
        this.docketFiledBy = Objects.requireNonNull(filedBy, "filedBy");
        this.docketFiledAt = Objects.requireNonNull(at, "at");
        this.updatedAt = at;
    }

    /** Whether a docket is filed; needed to resolve. */
    public boolean hasDocket() {
        return docketNo != null && !docketNo.isBlank();
    }

    /** A manager sets the domain from triage. */
    public void classifyManually(ComplaintDomain newDomain, Instant at) {
        this.domain = Objects.requireNonNull(newDomain, "domain");
        this.classifiedBy = ClassificationMethod.MANUAL;
        this.updatedAt = Objects.requireNonNull(at, "at");
    }

    /** Raises priority one step; {@code basePriority} is left alone. */
    public void escalatePriority(Instant at) {
        this.priority = this.priority.escalated();
        this.updatedAt = Objects.requireNonNull(at, "at");
    }

    /**
     * Manager downgrade to P3. Also moves {@code basePriority}, because it is a judgement about the work,
     * not ageing.
     */
    public void downgrade(Instant at) {
        this.basePriority = Priority.P3;
        this.priority = Priority.P3;
        this.updatedAt = Objects.requireNonNull(at, "at");
    }

    /** Whether ageing raised the current priority. */
    public boolean aged() {
        return priority != basePriority;
    }

    public void scheduleSla(Instant dueAt) {
        this.slaDueAt = dueAt;
    }

    /** Pauses the SLA clock while work is blocked (T12). */
    public void pauseSlaClock(Instant at) {
        this.holdStartedAt = Objects.requireNonNull(at, "at");
    }

    /**
     * Restarts the clock (T13) and pushes the due date back by the full hold time, since the attender
     * could not act during it.
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

    /** Removes the resident's email after 90 days. */
    public void purgeReporterContact() {
        this.reporterContact = null;
    }

    /** Replaces the raw text after a year. */
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

    /**
     * The complaint lifecycle states. Which moves are legal is defined by {@link ComplaintTransitions}.
     */
    public enum ComplaintStatus {
        NEW,
        TRIAGE,
        ASSIGNED,
        INSPECTING,
        AWAITING_CMS,
        IN_PROGRESS,
        ON_HOLD,
        RESOLVED,
        CLOSED,
        REJECTED;

        /** Whether no further transitions are possible. */
        public boolean isTerminal() {
            return this == CLOSED || this == REJECTED;
        }
    }

    /**
     * The maintenance domain of a complaint. Matches {@code ck_domain}.
     *
     * <p>{@link #UNCLASSIFIED} means "Others" or no answer; such complaints go to triage.
     */
    public enum ComplaintDomain {
        ELECTRICAL,
        CIVIL,
        RO_PLANT,
        NETWORK,
        UNCLASSIFIED;

        /** Whether dispatch can act on it, or it must go to triage. */
        public boolean isDispatchable() {
            return this != UNCLASSIFIED;
        }

        /**
         * The domain for the category a resident chose. Ignores case and spaces; accepts {@code LAN} for
         * {@link #NETWORK} and {@code RO} for {@link #RO_PLANT}. Anything else is {@link #UNCLASSIFIED}, so a
         * changed Form fills the triage queue instead of mislabelling complaints.
         */
        public static ComplaintDomain reported(String answer) {
            if (answer == null) {
                return UNCLASSIFIED;
            }
            return switch (answer.trim().toUpperCase().replace(' ', '_')) {
                case "ELECTRICAL", "ELECTRIC" -> ELECTRICAL;
                case "CIVIL" -> CIVIL;
                case "RO", "RO_PLANT", "RO_WATER", "WATER" -> RO_PLANT;
                case "LAN", "NETWORK", "INTERNET" -> NETWORK;
                default -> UNCLASSIFIED;
            };
        }
    }

    /**
     * Complaint priority. Matches {@code ck_priority}.
     *
     * <p>Ageing raises P3 → P2 → P1 once a complaint is open past 1.5× its SLA window, so old work does not
     * get buried.
     */
    public enum Priority {
        P1,
        P2,
        P3;

        /** One step up, capped at P1. */
        public Priority escalated() {
            return switch (this) {
                case P3 -> P2;
                case P2, P1 -> P1;
            };
        }
    }

    /** How a complaint's domain was decided. */
    public enum ClassificationMethod {

        /** The resident chose it on the form. */
        REPORTED,

        /** A manager set the domain from the triage queue. */
        MANUAL,

        /** The resident chose "Others", or chose nothing; the complaint went to triage. */
        FALLBACK
    }

    /**
     * The safety words that make a complaint P1 on arrival.
     *
     * <p>Exact whole-word matching on purpose: a fuzzy match could wrongly jump a routine complaint ahead
     * of real safety work, and substring matching would read "sparkling" as "spark".
     */
    public static final class SafetyKeywords {

        /** The keyword list; a phrase matches consecutive words. */
        private static final List<List<String>> PHRASES =
                List.of(
                        List.of("shock"),
                        List.of("spark"),
                        List.of("no", "water"),
                        List.of("sewage"),
                        List.of("short", "circuit"));

        private SafetyKeywords() {}

        /** Whether the text contains a safety keyword. */
        public static boolean hit(String text) {
            if (text == null || text.isBlank()) {
                return false;
            }
            List<String> words = List.of(text.toLowerCase().split("[^a-z0-9]+"));
            return PHRASES.stream().anyMatch(phrase -> containsInOrder(words, phrase));
        }

        /** P1 if a safety keyword appears, otherwise P2. */
        public static Priority basePriorityFor(String text) {
            return hit(text) ? Priority.P1 : Priority.P2;
        }

        private static boolean containsInOrder(List<String> words, List<String> phrase) {
            for (int start = 0; start + phrase.size() <= words.size(); start++) {
                if (words.subList(start, start + phrase.size()).equals(phrase)) {
                    return true;
                }
            }
            return false;
        }
    }
}
