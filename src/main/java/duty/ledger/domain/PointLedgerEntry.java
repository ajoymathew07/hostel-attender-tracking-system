package duty.ledger.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One immutable point movement (data model section 3.6).
 *
 * <p><strong>This table is the core integrity mechanism of the system.</strong> It is append-only:
 * no UPDATE, no DELETE. A reversal is a new row with negated points, never an edit of the original
 * (transition T17). The entity therefore exposes no mutators at all beyond what JPA needs to load
 * it.
 *
 * <p><strong>There is no balance column anywhere in the schema.</strong> A balance is a {@code SUM}
 * over these rows, so there is no counter to increment, therefore no lost update on a counter,
 * therefore nothing to reconcile (section 7.3).
 *
 * <p>{@code uq_ledger_idem} on {@code idempotencyKey} is what makes double-crediting structurally
 * impossible. The guarantee does not rest on transaction ordering, isolation level, retry logic or
 * on the application checking-then-inserting — which is itself a race. It rests on a database
 * uniqueness constraint, which the database enforces atomically however many concurrent
 * transactions attempt the insert.
 */
@Entity
@Table(name = "point_ledger")
public class PointLedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "attender_id", nullable = false)
    private Long attenderId;

    @Column(name = "task_id")
    private Long taskId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", nullable = false, length = 32)
    private ReasonCode reasonCode;

    /** The signed amount of this one movement. Never a running total. */
    @Column(name = "points", nullable = false)
    private int points;

    /** Asia/Kolkata business date (NFR-7), which may be backdated into a closed period. */
    @Column(name = "effective_date", nullable = false)
    private LocalDate effectiveDate;

    /** {@code 'YYYY-MM'}, always derived from {@link #effectiveDate} — never supplied. */
    @Column(name = "period_key", nullable = false, length = PeriodKey.LENGTH)
    private String periodKey;

    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "reverses_id")
    private Long reversesId;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** For JPA only. */
    protected PointLedgerEntry() {}

    private PointLedgerEntry(
            Long attenderId,
            Long taskId,
            ReasonCode reasonCode,
            int points,
            LocalDate effectiveDate,
            String note,
            String idempotencyKey,
            Long reversesId,
            Long createdBy,
            Instant createdAt) {
        this.attenderId = Objects.requireNonNull(attenderId, "attenderId");
        this.taskId = taskId;
        this.reasonCode = Objects.requireNonNull(reasonCode, "reasonCode");
        this.points = points;
        this.effectiveDate = Objects.requireNonNull(effectiveDate, "effectiveDate");
        this.periodKey = PeriodKey.of(this.effectiveDate);
        if (reasonCode.requiresNote() && (note == null || note.isBlank())) {
            throw new IllegalArgumentException(reasonCode + " requires a note");
        }
        this.note = note;
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        this.reversesId = reversesId;
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    /** The +2 for a resolved complaint (transition T14). */
    public static PointLedgerEntry credit(
            Long attenderId,
            Long taskId,
            ReasonCode reasonCode,
            int points,
            LocalDate effectiveDate,
            String idempotencyKey,
            Long createdBy,
            Instant createdAt) {
        return new PointLedgerEntry(
                attenderId,
                taskId,
                reasonCode,
                points,
                effectiveDate,
                null,
                idempotencyKey,
                null,
                createdBy,
                createdAt);
    }

    /** A manager-raised penalty or adjustment; the note is mandatory for an adjustment. */
    public static PointLedgerEntry managerEntry(
            Long attenderId,
            ReasonCode reasonCode,
            int points,
            LocalDate effectiveDate,
            String note,
            String idempotencyKey,
            Long createdBy,
            Instant createdAt) {
        return new PointLedgerEntry(
                attenderId,
                null,
                reasonCode,
                points,
                effectiveDate,
                note,
                idempotencyKey,
                null,
                createdBy,
                createdAt);
    }

    /**
     * The compensating row for a reopen (T17). Never a delete: the original credit and the reversal
     * both stay visible in the attender's history.
     */
    public PointLedgerEntry reversal(Long reversedBy, Instant at) {
        if (this.id == null) {
            throw new IllegalStateException("only a persisted entry can be reversed");
        }
        return new PointLedgerEntry(
                this.attenderId,
                this.taskId,
                ReasonCode.REVERSAL,
                -this.points,
                this.effectiveDate,
                null,
                IdempotencyKeys.forReversal(this.id),
                this.id,
                Objects.requireNonNull(reversedBy, "reversedBy"),
                Objects.requireNonNull(at, "at"));
    }

    public Long getId() {
        return id;
    }

    public Long getAttenderId() {
        return attenderId;
    }

    public Long getTaskId() {
        return taskId;
    }

    public ReasonCode getReasonCode() {
        return reasonCode;
    }

    public int getPoints() {
        return points;
    }

    public LocalDate getEffectiveDate() {
        return effectiveDate;
    }

    public String getPeriodKey() {
        return periodKey;
    }

    public String getNote() {
        return note;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Long getReversesId() {
        return reversesId;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
