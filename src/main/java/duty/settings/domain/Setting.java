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
 * One manager-set override of a configuration property, keyed by the property's YAML name.
 *
 * <p>The value is stored as text and parsed by the module that owns the key. Deleting the row restores
 * the YAML default.
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
