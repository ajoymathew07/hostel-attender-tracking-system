package duty.dispatch.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.Objects;

/**
 * Which attender covers which floor, and how (data model section 3.4).
 *
 * <p>Current state only. An earlier draft made coverage time-bounded so a past dispatch decision
 * could be replayed against the roster then in force, but coverage at Cauvery is fixed rather than
 * rotating (OQ-3), and explainability is already served more cheaply by
 * {@code task_assignment.score_snapshot}.
 *
 * <p>{@code attenderId} is a plain column rather than an association: {@code AppUser} belongs to
 * the identity module, and cross-module references go through the published service interface, not
 * through another module's entities (architecture section 2.4, rule 3).
 */
@Entity
@Table(name = "floor_assignment")
public class FloorAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "attender_id", nullable = false)
    private Long attenderId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "floor_id", nullable = false)
    private Floor floor;

    @Enumerated(EnumType.STRING)
    @Column(name = "coverage", nullable = false, length = 16)
    private Coverage coverage;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /** For JPA only. */
    protected FloorAssignment() {}

    public FloorAssignment(Long attenderId, Floor floor, Coverage coverage) {
        this.attenderId = Objects.requireNonNull(attenderId, "attenderId");
        this.floor = Objects.requireNonNull(floor, "floor");
        this.coverage = Objects.requireNonNull(coverage, "coverage");
    }

    public void changeCoverage(Coverage newCoverage) {
        this.coverage = Objects.requireNonNull(newCoverage, "coverage");
    }

    public boolean isPrimary() {
        return coverage == Coverage.PRIMARY;
    }

    public Long getId() {
        return id;
    }

    public Long getAttenderId() {
        return attenderId;
    }

    public Floor getFloor() {
        return floor;
    }

    public Coverage getCoverage() {
        return coverage;
    }

    public long getVersion() {
        return version;
    }
}
