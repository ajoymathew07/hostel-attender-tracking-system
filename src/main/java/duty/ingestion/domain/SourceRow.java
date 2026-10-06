package duty.ingestion.domain;

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
 * One observed source record — the idempotency anchor for ingestion (data model section 3.5).
 *
 * <p><strong>{@code uq_source_row} is the invariant this module owns:</strong> a source row is
 * never turned into two complaints. Identity is {@code (source_type, source_ref, response_key)}
 * where {@code response_key} is a stable hash of the submission, never the row's position — a
 * deleted row shifting every index below it must produce zero spurious amendments (test plan E11).
 */
@Entity
@Table(name = "source_row")
public class SourceRow {

    /** SHA-256, hex encoded. Fixed width is enforced here rather than by the column type. */
    public static final int HASH_LENGTH = 64;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 16)
    private SourceType sourceType;

    /** Sheet id, or upload batch id. */
    @Column(name = "source_ref", nullable = false, length = 128)
    private String sourceRef;

    /** SHA-256(timestamp || reporter email). Stable identity across runs. */
    @Column(name = "response_key", nullable = false, length = HASH_LENGTH)
    private String responseKey;

    /** SHA-256 of all normalized cells. Change detection only, never identity. */
    @Column(name = "content_hash", nullable = false, length = HASH_LENGTH)
    private String contentHash;

    /** Positional hint only (section 5.3). */
    @Column(name = "row_index")
    private Integer rowIndex;

    @Column(name = "raw_payload", nullable = false, columnDefinition = "text")
    private String rawPayload;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    private SourceRowState state;

    @Column(name = "revision", nullable = false)
    private int revision;

    @Column(name = "first_seen_run", nullable = false)
    private Long firstSeenRunId;

    @Column(name = "last_seen_run", nullable = false)
    private Long lastSeenRunId;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    /**
     * Set once the row has been normalized into a complaint. Deliberately not a foreign key:
     * {@code complaint.source_row_id} already points the other way and declaring both would make
     * the pair circular (section 3.5).
     */
    @Column(name = "complaint_id")
    private Long complaintId;

    /** For JPA only. */
    protected SourceRow() {}

    public SourceRow(
            SourceType sourceType,
            String sourceRef,
            String responseKey,
            String contentHash,
            Integer rowIndex,
            String rawPayload,
            Long observedInRunId,
            Instant observedAt) {
        this.sourceType = Objects.requireNonNull(sourceType, "sourceType");
        this.sourceRef = Objects.requireNonNull(sourceRef, "sourceRef");
        this.responseKey = requireHash(responseKey, "responseKey");
        this.contentHash = requireHash(contentHash, "contentHash");
        this.rowIndex = rowIndex;
        this.rawPayload = Objects.requireNonNull(rawPayload, "rawPayload");
        this.state = SourceRowState.ACTIVE;
        this.revision = 1;
        this.firstSeenRunId = Objects.requireNonNull(observedInRunId, "observedInRunId");
        this.lastSeenRunId = this.firstSeenRunId;
        this.firstSeenAt = Objects.requireNonNull(observedAt, "observedAt");
        this.lastSeenAt = this.firstSeenAt;
    }

    private static String requireHash(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.length() != HASH_LENGTH) {
            throw new IllegalArgumentException(
                    field + " must be a " + HASH_LENGTH + "-character SHA-256 hex digest");
        }
        return value;
    }

    /** A later run saw this same row again: a no-op for identity, but the watermark moves. */
    public void seenAgain(Long runId, Instant at) {
        this.lastSeenRunId = Objects.requireNonNull(runId, "runId");
        this.lastSeenAt = Objects.requireNonNull(at, "at");
    }

    public void linkToComplaint(Long newComplaintId) {
        this.complaintId = Objects.requireNonNull(newComplaintId, "complaintId");
    }

    /**
     * Whether the source content changed since this row was recorded. The stored hash survives the
     * 90-day purge of {@code raw_payload}, which is what lets retention coexist with dedupe
     * (section 3.8).
     */
    public boolean contentChangedFrom(String observedContentHash) {
        return !this.contentHash.equals(observedContentHash);
    }

    /** Purges the payload while keeping the hash, so dedupe still works (section 3.8). */
    public void purgeRawPayload() {
        this.rawPayload = "";
    }

    public Long getId() {
        return id;
    }

    public SourceType getSourceType() {
        return sourceType;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public String getResponseKey() {
        return responseKey;
    }

    public String getContentHash() {
        return contentHash;
    }

    public Integer getRowIndex() {
        return rowIndex;
    }

    public String getRawPayload() {
        return rawPayload;
    }

    public SourceRowState getState() {
        return state;
    }

    public int getRevision() {
        return revision;
    }

    public Long getFirstSeenRunId() {
        return firstSeenRunId;
    }

    public Long getLastSeenRunId() {
        return lastSeenRunId;
    }

    public Instant getFirstSeenAt() {
        return firstSeenAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public Long getComplaintId() {
        return complaintId;
    }
}
