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
 * One recorded change. Append-only: no setters, and the repository has no delete method.
 *
 * <p>{@code (entityType, entityId)} is not a foreign key because one table logs changes to many
 * tables. {@code actorId} is {@code null} when the system acted.
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

    /** The attender the manager acted for (act-as); {@code null} otherwise. */
    @Column(name = "on_behalf_of")
    private Long onBehalfOf;

    /** For JPA only. */
    protected AuditEvent() {}

    /** The only way to build one. Column constraints, not this constructor, validate the values. */
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
            String correlationId,
            Long onBehalfOf) {
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
        this.onBehalfOf = onBehalfOf;
    }

    /** Whether the system rather than a person made this change. */
    public boolean isSystemAction() {
        return actorId == null;
    }

    /** Whether this row records a state transition, as opposed to a field-level change. */
    public boolean isStateTransition() {
        return fromState != null || toState != null;
    }

    public Long getOnBehalfOf() {
        return onBehalfOf;
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
