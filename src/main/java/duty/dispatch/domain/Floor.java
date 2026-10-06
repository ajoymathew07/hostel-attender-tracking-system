package duty.dispatch.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;

/**
 * A physical floor of the hostel (data model section 3.4).
 *
 * <p>Identity is {@code uq_floor (wing, floor_number)}: the same numbered floor in two wings is two
 * rows, and the same one twice is rejected.
 */
@Entity
@Table(name = "floor")
public class Floor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "wing", nullable = false, length = 16)
    private String wing;

    @Column(name = "floor_number", nullable = false)
    private int floorNumber;

    @Column(name = "label", nullable = false, length = 64)
    private String label;

    /** For JPA only. */
    protected Floor() {}

    public Floor(String wing, int floorNumber, String label) {
        this.wing = Objects.requireNonNull(wing, "wing");
        this.floorNumber = floorNumber;
        this.label = Objects.requireNonNull(label, "label");
    }

    public void relabel(String newLabel) {
        this.label = Objects.requireNonNull(newLabel, "label");
    }

    public Long getId() {
        return id;
    }

    public String getWing() {
        return wing;
    }

    public int getFloorNumber() {
        return floorNumber;
    }

    public String getLabel() {
        return label;
    }
}
