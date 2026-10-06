package duty.ingestion.domain;

import java.util.Locale;
import java.util.Optional;

/**
 * The fields ingestion reads from a row. Each is found by header name, never position, so reordering
 * Form questions is safe.
 *
 * <p>Name, roll number and phone are optional; they let the attender contact the student.
 */
public enum SourceField {
    TIMESTAMP("timestamp"),
    EMAIL("email"),
    NAME("name"),
    ROLL_NO("rollNo"),
    PHONE("phone"),
    ROOM("room"),
    DESCRIPTION("description"),

    /** The category the resident chose on the form: Civil, Electrical, LAN, RO or Others. */
    CATEGORY("category");

    private final String configKey;

    SourceField(String configKey) {
        this.configKey = configKey;
    }

    /** The key this field carries in {@code application.yml}, e.g. {@code rollNo}. */
    public String configKey() {
        return configKey;
    }

    /**
     * Finds a field by its YAML key, accepting {@code rollNo}, {@code roll-no} or {@code ROLL_NO}. Spring
     * does not relax map keys, so without this a variant spelling would silently map nothing.
     */
    public static Optional<SourceField> byConfigKey(String key) {
        if (key == null) {
            return Optional.empty();
        }
        String wanted = canonical(key);
        for (SourceField field : values()) {
            if (canonical(field.configKey).equals(wanted)) {
                return Optional.of(field);
            }
        }
        return Optional.empty();
    }

    private static String canonical(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
