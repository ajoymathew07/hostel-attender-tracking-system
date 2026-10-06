package duty.dispatch.domain;

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
 * A period when an attender is available, on leave or off duty. {@code endsAt == null} means
 * open-ended.
 */
@Entity
@Table(name = "availability_window")
public class AvailabilityWindow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "attender_id", nullable = false)
    private Long attenderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private AvailabilityStatus status;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at")
    private Instant endsAt;

    @Column(name = "reason", length = 255)
    private String reason;

    @Column(name = "recorded_by", nullable = false)
    private Long recordedBy;

    /** For JPA only. */
    protected AvailabilityWindow() {}

    public AvailabilityWindow(
            Long attenderId,
            AvailabilityStatus status,
            Instant startsAt,
            Instant endsAt,
            String reason,
            Long recordedBy) {
        this.attenderId = Objects.requireNonNull(attenderId, "attenderId");
        this.status = Objects.requireNonNull(status, "status");
        this.startsAt = Objects.requireNonNull(startsAt, "startsAt");
        if (endsAt != null && endsAt.isBefore(startsAt)) {
            throw new IllegalArgumentException("endsAt must not precede startsAt");
        }
        this.endsAt = endsAt;
        this.reason = reason;
        this.recordedBy = Objects.requireNonNull(recordedBy, "recordedBy");
    }

    /** Whether this window is in force at the given instant. End is exclusive. */
    public boolean coversInstant(Instant at) {
        Objects.requireNonNull(at, "at");
        if (at.isBefore(startsAt)) {
            return false;
        }
        return endsAt == null || at.isBefore(endsAt);
    }

    /** Closes an open-ended window, e.g. when an attender returns early from leave. */
    public void closeAt(Instant at) {
        Objects.requireNonNull(at, "at");
        if (at.isBefore(startsAt)) {
            throw new IllegalArgumentException("endsAt must not precede startsAt");
        }
        this.endsAt = at;
    }

    public Long getId() {
        return id;
    }

    public Long getAttenderId() {
        return attenderId;
    }

    public AvailabilityStatus getStatus() {
        return status;
    }

    public Instant getStartsAt() {
        return startsAt;
    }

    public Instant getEndsAt() {
        return endsAt;
    }

    public String getReason() {
        return reason;
    }

    public Long getRecordedBy() {
        return recordedBy;
    }
}
