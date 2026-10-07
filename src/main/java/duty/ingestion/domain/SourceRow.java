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
 * One row seen in a source: what stops a response becoming two complaints.
 *
 * <p>Unique on {@code (source_type, source_ref, response_key)}. The key is a hash of the submission,
 * not its row number, so deleting a sheet row creates no duplicates.
 */
@Entity
@Table(name = "source_row")
public class SourceRow {

    /** SHA-256, hex encoded. */
    public static final int HASH_LENGTH = 64;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 16)
    private SourceType sourceType;

    /** The sheet id, or the CSV channel. */
    @Column(name = "source_ref", nullable = false, length = 128)
    private String sourceRef;

    /** Hash of timestamp and email: the row's identity across runs. */
    @Column(name = "response_key", nullable = false, length = HASH_LENGTH)
    private String responseKey;

    /** Hash of all cells: change detection only. */
    @Column(name = "content_hash", nullable = false, length = HASH_LENGTH)
    private String contentHash;

    /** Row number in the source, used only to know where to resume reading. */
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
     * The complaint made from this row. Not a foreign key, because {@code complaint.source_row_id}
     * already points the other way.
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

    /** A later run saw this row again. */
    public void seenAgain(Long runId, Instant at) {
        this.lastSeenRunId = Objects.requireNonNull(runId, "runId");
        this.lastSeenAt = Objects.requireNonNull(at, "at");
    }

    public void linkToComplaint(Long newComplaintId) {
        this.complaintId = Objects.requireNonNull(newComplaintId, "complaintId");
    }

    /**
     * Whether the content changed. The hash is kept after the payload is purged, so this still works.
     */
    public boolean contentChangedFrom(String observedContentHash) {
        return !this.contentHash.equals(observedContentHash);
    }

    /** Deletes the raw payload (after 90 days) but keeps the hash, so dedupe still works. */
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

    /** Matches {@code ck_sr_state}. */
    public enum SourceRowState {

        /** The row as last observed in the source. */
        ACTIVE,

        /**
         * Reserved for edited responses, which are not handled yet; an edited row is currently skipped.
         */
        AMENDED
    }

    /** Where a row came from. */
    public enum SourceType {

        /** The Google Form response sheet. */
        GOOGLE_SHEETS,

        /** A CSV uploaded by the manager. */
        CSV_UPLOAD
    }
}
