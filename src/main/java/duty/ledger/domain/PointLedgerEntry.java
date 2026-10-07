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
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * One immutable point movement. Append-only: no updates, no deletes.
 *
 * <p>There is no balance column; a balance is a {@code SUM} of these rows. The unique idempotency key
 * is what makes double-crediting impossible.
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

    /** The Asia/Kolkata business date. */
    @Column(name = "effective_date", nullable = false)
    private LocalDate effectiveDate;

    /** {@code YYYY-MM}, always derived from {@link #effectiveDate}. */
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

    /** The negating row for a reopen. The original credit is kept. */
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

    /** Why a point movement happened. Matches the {@code ck_ledger_reason} constraint. */
    public enum ReasonCode {

        /** +2 by default, on transition to RESOLVED. Key: {@code RESOLVE:{task}:{attender}}. */
        COMPLAINT_RESOLVED,

        /** {@code task.point_value}, on duty completion. Key: {@code DUTY:{task}:{attender}}. */
        SPECIAL_DUTY,

        /** −1 by default, raised by the manager. Key: {@code PENALTY:{uuid}} from the request. */
        PENALTY,

        /** Any amount, raised by the manager with a mandatory note. Key: {@code ADJ:{uuid}}. */
        MANUAL_ADJUSTMENT,

        /** The negation of a target row, never a delete. Key: {@code REV:{reverses_id}}. */
        REVERSAL;

        /** Whether the manager must supply a note for this code. */
        public boolean requiresNote() {
            return this == MANUAL_ADJUSTMENT;
        }
    }

    /**
     * Builds the ledger's idempotency keys.
     *
     * <p>Automatic credits are keyed on the task and attender, never on the request, so a retried request
     * collides on the unique key and cannot credit twice.
     */
    public static final class IdempotencyKeys {

        private IdempotencyKeys() {}

        /**
         * {@code RESOLVE:{task}:{attender}}, plus {@code :r{n}} after a reopen so a genuine re-resolution is
         * credited again.
         */
        public static String forResolve(Long taskId, Long attenderId, int reopenCount) {
            if (reopenCount < 0) {
                throw new IllegalArgumentException("reopenCount must not be negative");
            }
            String base = "RESOLVE:" + require(taskId, "taskId") + ":" + require(attenderId, "attenderId");
            return reopenCount > 0 ? base + ":r" + reopenCount : base;
        }

        /** {@code DUTY:{task}:{attender}}: one key per assignee of a shared duty. */
        public static String forSpecialDuty(Long taskId, Long attenderId) {
            return "DUTY:" + require(taskId, "taskId") + ":" + require(attenderId, "attenderId");
        }

        /** {@code PENALTY:{uuid}} — the key comes from the manager's request. */
        public static String forPenalty(String requestId) {
            return "PENALTY:" + requireText(requestId, "requestId");
        }

        /** {@code ADJ:{uuid}} — the key comes from the manager's request. */
        public static String forAdjustment(String requestId) {
            return "ADJ:" + requireText(requestId, "requestId");
        }

        /** {@code REV:{reverses_id}} — one reversal per target row, enforced by the constraint. */
        public static String forReversal(Long reversesId) {
            return "REV:" + require(reversesId, "reversesId");
        }

        private static Long require(Long value, String field) {
            return Objects.requireNonNull(value, field);
        }

        private static String requireText(String value, String field) {
            Objects.requireNonNull(value, field);
            if (value.isBlank()) {
                throw new IllegalArgumentException(field + " must not be blank");
            }
            return value;
        }
    }

    /**
     * Derives {@code period_key} ({@code YYYY-MM}) from the Asia/Kolkata date, not the UTC instant.
     *
     * <p>Why: a job finished at 02:00 IST on 1 October belongs to October, though it is still September in
     * UTC.
     */
    public static final class PeriodKey {

        /** The business timezone. Timestamps are stored UTC; the *business date* is local. */
        public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Kolkata");

        /** Width of a {@code YYYY-MM} key, matching the column. */
        public static final int LENGTH = 7;

        private PeriodKey() {}

        /** {@code 'YYYY-MM'} for a business date. */
        public static String of(LocalDate effectiveDate) {
            Objects.requireNonNull(effectiveDate, "effectiveDate");
            return String.format("%04d-%02d", effectiveDate.getYear(), effectiveDate.getMonthValue());
        }

        /** The Asia/Kolkata business date of a UTC instant. */
        public static LocalDate businessDateOf(java.time.Instant instant) {
            Objects.requireNonNull(instant, "instant");
            return ZonedDateTime.ofInstant(instant, BUSINESS_ZONE).toLocalDate();
        }

        /** {@code 'YYYY-MM'} for the business date on which an instant falls. */
        public static String ofInstant(java.time.Instant instant) {
            return of(businessDateOf(instant));
        }
    }

    /** One attender's summed points for one period, as returned by the leaderboard query. */
    public record PeriodTotal(Long attenderId, Long total) {}
}
