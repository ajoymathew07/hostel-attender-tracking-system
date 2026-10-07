package duty.ingestion.domain;

import duty.ingestion.domain.ColumnMapping.SourceField;
import duty.ingestion.domain.SourceRow.SourceType;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Where complaint rows come from: the Google Sheet or an uploaded CSV.
 *
 * <p>Both produce the same raw rows, so everything downstream (dedupe, normalization, complaint
 * creation) is written once. That is why re-uploading a CSV creates no duplicates.
 */
public interface ComplaintSource {

    /**
     * Identifies the source within its type: the sheet id, or the CSV channel. Must be the same on every
     * upload, or re-uploading would duplicate every complaint.
     */
    String sourceRef();

    /** Which kind of source this is. */
    SourceType type();

    /**
     * Reads rows from the source.
     *
     * @throws MissingRequiredColumnException if a required column is missing; the run fails
     */
    List<RawRow> fetch(FetchWindow window);

    /**
     * Which rows of a source to read.
     *
     * <p>For Sheets: from the last row seen minus an overlap, which absorbs deleted rows. A CSV is read
     * whole ({@link #all()}).
     *
     * @param fromRowIndex the first row to read, 1-based (row 1 is the header)
     */
    public record FetchWindow(int fromRowIndex) {

        public FetchWindow {
            if (fromRowIndex < 1) {
                throw new IllegalArgumentException("fromRowIndex is 1-based; got " + fromRowIndex);
            }
        }

        /** Everything the source holds. */
        public static FetchWindow all() {
            return new FetchWindow(1);
        }
    }

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
}
