package duty.ingestion.domain;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Where each field sits in this source's rows. Resolved once per run. */
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
