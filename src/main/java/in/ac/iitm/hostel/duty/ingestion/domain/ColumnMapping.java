package in.ac.iitm.hostel.duty.ingestion.domain;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The configured header aliases for each logical field, and which fields are required (section
 * 5.2).
 *
 * <p>Each field lists the header names we accept. At the start of a run headers are resolved once;
 * an unresolvable <strong>required</strong> column fails the whole run loudly rather than ingesting
 * garbage.
 *
 * <p>Both the aliases and the required set are configuration, so a Form rename is a YAML edit
 * rather than a redeploy.
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
     * Resolves this source's header row into column positions.
     *
     * <p>Matching folds case, padding and doubled whitespace, because a header typed by a human
     * into a Google Form regularly differs from the configured alias in exactly those ways and in
     * no others that matter. Where two headers would resolve the same field, the leftmost wins.
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
}
