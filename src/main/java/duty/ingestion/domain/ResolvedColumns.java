package duty.ingestion.domain;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Where each logical field sits in <em>this</em> source's rows — the result of resolving headers
 * once, at the start of a run (section 5.2).
 *
 * <p>Resolution happens once rather than per row for the obvious reason, and for a less obvious
 * one: a header resolved once cannot drift half way down a file.
 */
public final class ResolvedColumns {

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
     * The cell for a field, or {@code null} when the column is absent <em>or</em> the row is short.
     *
     * <p>A short row is normal in CSV: trailing empty answers are frequently omitted entirely
     * rather than written as empty fields, so treating it as an error would reject valid data.
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
