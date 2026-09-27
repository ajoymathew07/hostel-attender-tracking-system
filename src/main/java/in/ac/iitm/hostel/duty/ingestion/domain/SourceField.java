package in.ac.iitm.hostel.duty.ingestion.domain;

import java.util.Locale;
import java.util.Optional;

/**
 * The logical fields ingestion reads out of a source row (section 5.2).
 *
 * <p>Each one is resolved by <strong>header name</strong> from configuration, never by position:
 * form question wording and column order will change (OQ-4), and a positional mapping would
 * silently start reading the wrong answers the first time the manager reorders a question.
 *
 * <p>{@link #NAME}, {@link #ROLL_NO} and {@link #PHONE} are mapped because the assigned attender
 * needs to identify and reach the student (section 11.4). They are optional: a Form that does not
 * ask for them still ingests, and the attender sees the room number alone.
 */
public enum SourceField {
    TIMESTAMP("timestamp"),
    EMAIL("email"),
    NAME("name"),
    ROLL_NO("rollNo"),
    PHONE("phone"),
    ROOM("room"),
    DESCRIPTION("description"),

    /** The resident's own guess at the category — a hint for the classifier, never a decision. */
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
     * Resolves a configuration key to a field, tolerating {@code rollNo}, {@code roll-no} and
     * {@code ROLL_NO} alike.
     *
     * <p>The leniency is deliberate. Spring's relaxed binding does not apply to map keys, so
     * without this a manager writing {@code roll_no} in YAML would get a silently unmapped column
     * rather than an error.
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
