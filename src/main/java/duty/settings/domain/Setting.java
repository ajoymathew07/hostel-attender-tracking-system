package duty.settings.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;

/**
 * One manager-set override of a configuration property.
 *
 * <p>The {@code name} is the property's own key — {@code dispatch.weights.floor} — so a row here
 * and the line it overrides in {@code application.yml} are recognisably the same thing. The value
 * is text, parsed by whichever module owns the key: this module stores settings and does not
 * pretend to understand them.
 *
 * <p>Absence is the default. Deleting a row restores the YAML value, which is why there is no
 * "reset to default" column.
 */
@Entity
@Table(name = "app_setting")
public class Setting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "value", nullable = false, length = 255)
    private String value;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    /** For JPA only. */
    protected Setting() {}

    public Setting(String name, String value, Instant updatedAt, Long updatedBy) {
        this.name = Objects.requireNonNull(name, "name");
        this.value = Objects.requireNonNull(value, "value");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.updatedBy = updatedBy;
    }

    public void changeTo(String newValue, Instant at, Long by) {
        this.value = Objects.requireNonNull(newValue, "value");
        this.updatedAt = Objects.requireNonNull(at, "at");
        this.updatedBy = by;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getValue() {
        return value;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getUpdatedBy() {
        return updatedBy;
    }
}
