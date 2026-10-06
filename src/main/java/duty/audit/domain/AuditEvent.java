package duty.audit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;

/**
 * One recorded change (data model section 3.7).
 *
 * <p><strong>Append-only by construction.</strong> There is no setter on this class and no delete
 * path to it: {@code AuditEventRepository} deliberately extends the bare
 * {@code Repository} marker rather than {@code JpaRepository}, so no {@code delete} method exists
 * to call. Operational data is tombstoned rather than hard-deleted precisely so that this trail
 * stays intact (section 3.1, principle 4), and the trail itself is retained indefinitely for bonus
 * dispute resolution (section 3.8).
 *
 * <p><strong>Written in the same transaction as the change it records.</strong> The row is inserted
 * by a {@code @TransactionalEventListener(BEFORE_COMMIT)} handler, so the log can never disagree
 * with the data: if the change rolls back, so does its audit row, and if the audit row cannot be
 * written, the change rolls back with it.
 *
 * <p><strong>The aggregate reference is {@code (entityType, entityId)}, not a foreign key.</strong>
 * One table records changes to many different tables, so there is no single table for
 * {@code entityId} to reference. {@code actorId} <em>is</em> a foreign key, because an actor is
 * always an {@code app_user} — or {@code null}, meaning the system acted.
 */
@Entity
@Table(name = "audit_event")
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    /** {@code null} means the system acted — a scheduled job, or ingestion. */
    @Column(name = "actor_id")
    private Long actorId;

    @Column(name = "actor_role", length = 16)
    private String actorRole;

    @Column(name = "entity_type", nullable = false, length = 32)
    private String entityType;

    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    @Column(name = "event_type", nullable = false, length = 48)
    private String eventType;

    @Column(name = "from_state", length = 24)
    private String fromState;

    @Column(name = "to_state", length = 24)
    private String toState;

    /** JSON string. Holds the before and after values of a corrected docket, for instance. */
    @Column(name = "detail", columnDefinition = "text")
    private String detail;

    /** Ties every row written by one request or one ingestion run together. */
    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    /** For JPA only. */
    protected AuditEvent() {}

    /**
     * The only way to build one. There is no setter, so a row cannot be altered after construction.
     *
     * <p>{@code entityType} and {@code eventType} are deliberately <em>not</em> validated here: the
     * {@code NOT NULL} and length constraints on the columns are the authority, and letting the
     * database reject a bad row is what proves the audit write really does share the business
     * transaction.
     */
    public AuditEvent(
            Instant occurredAt,
            Long actorId,
            String actorRole,
            String entityType,
            Long entityId,
            String eventType,
            String fromState,
            String toState,
            String detail,
            String correlationId) {
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        this.actorId = actorId;
        this.actorRole = actorRole;
        this.entityType = entityType;
        this.entityId = entityId;
        this.eventType = eventType;
        this.fromState = fromState;
        this.toState = toState;
        this.detail = detail;
        this.correlationId = correlationId;
    }

    /** Whether the system rather than a person made this change. */
    public boolean isSystemAction() {
        return actorId == null;
    }

    /** Whether this row records a state transition, as opposed to a field-level change. */
    public boolean isStateTransition() {
        return fromState != null || toState != null;
    }

    public Long getId() {
        return id;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Long getActorId() {
        return actorId;
    }

    public String getActorRole() {
        return actorRole;
    }

    public String getEntityType() {
        return entityType;
    }

    public Long getEntityId() {
        return entityId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getFromState() {
        return fromState;
    }

    public String getToState() {
        return toState;
    }

    public String getDetail() {
        return detail;
    }

    public String getCorrelationId() {
        return correlationId;
    }
}
