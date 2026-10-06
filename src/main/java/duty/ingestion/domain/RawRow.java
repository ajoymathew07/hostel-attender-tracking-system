package duty.ingestion.domain;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One row as a source read it: headers, cells, and the cells mapped to fields.
 *
 * <p>Separate from the {@code SourceRow} entity because sources know only cells; keys and hashes are
 * computed later by the pipeline.
 *
 * @param rowIndex position in the source, for diagnostics only
 */
public record RawRow(int rowIndex, List<String> headers, List<String> cells, Map<SourceField, String> fields) {

    public RawRow {
        headers = List.copyOf(Objects.requireNonNull(headers, "headers"));
        cells = List.copyOf(Objects.requireNonNull(cells, "cells"));
        fields = Map.copyOf(Objects.requireNonNull(fields, "fields"));
    }

    /** The cell for a field, or {@code null} when this source does not carry it. */
    public String get(SourceField field) {
        return fields.get(field);
    }

    /** Position and size only; cells hold student details and must not reach a log. */
    @Override
    public String toString() {
        return "RawRow[rowIndex=" + rowIndex + ", cells=" + cells.size() + "]";
    }
}
