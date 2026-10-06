package duty.ingestion.domain;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One row as a {@link ComplaintSource} read it — the shape both sources produce (section 2.2).
 *
 * <p>This is deliberately <em>not</em> {@code SourceRow}. {@code SourceRow} is the persistent
 * idempotency anchor and carries a response key, a content hash and the run that observed it, all
 * of which the pipeline computes. An adapter knows none of that; it knows cells. Keeping the two
 * types apart is what lets the Sheets adapter drop in behind the same port without either
 * adapter learning anything about dedupe.
 *
 * @param rowIndex the row's position in the source — a hint for diagnostics, never identity
 * @param headers the source's header row, kept so the raw payload can be stored faithfully
 * @param cells every cell of this row, in source order, for the content hash
 * @param fields the cells the column mapping resolved, by logical field
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

    /**
     * Positional and structural only.
     *
     * <p>The generated record {@code toString} would print every cell, and cells hold the
     * student's name, roll number and phone number. Those must never be written to a log or appear
     * in a stack trace (section 11.4), and a row is precisely the object a debug statement reaches
     * for.
     */
    @Override
    public String toString() {
        return "RawRow[rowIndex=" + rowIndex + ", cells=" + cells.size() + "]";
    }
}
