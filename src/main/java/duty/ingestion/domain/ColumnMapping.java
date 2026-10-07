package duty.ingestion.domain;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The accepted header names for each field, and which fields are required.
 *
 * <p>Headers are matched by name, not position, and configured in YAML, so renaming a Form question
 * needs no redeploy. A missing required column fails the whole run.
 */
public final class ColumnMapping {

    private final Map<SourceField, List<String>> aliases;
    private final Set<SourceField> required;

    public ColumnMapping(Map<SourceField, List<String>> aliases, Set<SourceField> required) {
        Objects.requireNonNull(aliases, "aliases");
        Objects.requireNonNull(required, "required");

        this.aliases = new EnumMap<>(SourceField.class);
        aliases.forEach((field, names) -> this.aliases.put(field, List.copyOf(names)));

        this.required = required.isEmpty() ? EnumSet.noneOf(SourceField.class) : EnumSet.copyOf(required);
        for (SourceField field : this.required) {
            if (this.aliases.getOrDefault(field, List.of()).isEmpty()) {
                throw new IllegalArgumentException(
                        "required field '" + field.configKey() + "' has no configured header aliases");
            }
        }
    }

    public Set<SourceField> required() {
        return EnumSet.copyOf(required);
    }

    public List<String> aliasesFor(SourceField field) {
        return aliases.getOrDefault(field, List.of());
    }

    /**
     * Finds each field's column. Ignores case and extra spaces; if two headers match, the leftmost wins.
     *
     * @throws MissingRequiredColumnException if a required field matches no header
     */
    public ResolvedColumns resolve(List<String> headers) {
        Objects.requireNonNull(headers, "headers");

        List<String> canonicalHeaders = new ArrayList<>(headers.size());
        for (String header : headers) {
            canonicalHeaders.add(canonical(header));
        }

        Map<SourceField, Integer> indexes = new EnumMap<>(SourceField.class);
        for (Map.Entry<SourceField, List<String>> entry : aliases.entrySet()) {
            findColumn(canonicalHeaders, entry.getValue())
                    .ifPresent(index -> indexes.put(entry.getKey(), index));
        }

        for (SourceField field : required) {
            if (!indexes.containsKey(field)) {
                throw new MissingRequiredColumnException(field, aliasesFor(field));
            }
        }
        return new ResolvedColumns(indexes);
    }

    private static java.util.Optional<Integer> findColumn(List<String> canonicalHeaders, List<String> names) {
        for (String name : names) {
            int index = canonicalHeaders.indexOf(canonical(name));
            if (index >= 0) {
                return java.util.Optional.of(index);
            }
        }
        return java.util.Optional.empty();
    }

    private static String canonical(String header) {
        if (header == null) {
            return "";
        }
        return header.replace(' ', ' ').trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** Where each field sits in this source's rows. Resolved once per run. */
    public static final class ResolvedColumns {

        private final Map<SourceField, Integer> indexes;

        public ResolvedColumns(Map<SourceField, Integer> indexes) {
            this.indexes = new EnumMap<>(Objects.requireNonNull(indexes, "indexes"));
        }

        /** Whether this source carries the field at all. Optional columns legitimately do not. */
        public boolean has(SourceField field) {
            return indexes.containsKey(field);
        }

        /** The column position, or {@code null} when the source does not carry this field. */
        public Integer indexOf(SourceField field) {
            return indexes.get(field);
        }

        /**
         * The cell for a field, or {@code null} if the column is absent or the row is short (CSV often drops
         * trailing empty cells).
         */
        public String valueIn(List<String> cells, SourceField field) {
            Integer index = indexes.get(field);
            if (index == null || index >= cells.size()) {
                return null;
            }
            return cells.get(index);
        }

        /** Every field this source carries, read out of one row. */
        public Map<SourceField, String> extract(List<String> cells) {
            Map<SourceField, String> values = new EnumMap<>(SourceField.class);
            for (SourceField field : indexes.keySet()) {
                String value = valueIn(cells, field);
                if (value != null) {
                    values.put(field, value);
                }
            }
            return values;
        }
    }

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

    /**
     * A required column is missing from the headers. Fails the whole run, so the manager notices instead
     * of complaints silently going missing.
     *
     * <p>The message names the field and the accepted header names, so the Form can be fixed.
     */
    public static class MissingRequiredColumnException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final transient SourceField field;

        public MissingRequiredColumnException(SourceField field, List<String> acceptedAliases) {
            super(
                    "required column '"
                            + field.configKey()
                            + "' was not found; accepted headers are "
                            + String.join(", ", acceptedAliases));
            this.field = field;
        }

        public SourceField getField() {
            return field;
        }
    }
}
