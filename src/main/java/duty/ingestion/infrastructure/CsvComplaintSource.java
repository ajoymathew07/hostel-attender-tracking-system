package duty.ingestion.infrastructure;

import duty.ingestion.domain.ColumnMapping;
import duty.ingestion.domain.ComplaintSource;
import duty.ingestion.domain.FetchWindow;
import duty.ingestion.domain.RawRow;
import duty.ingestion.domain.ResolvedColumns;
import duty.ingestion.domain.SourceType;
import duty.ingestion.domain.UploadLimits;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A manager-uploaded CSV as a {@link ComplaintSource}. It only turns bytes into rows; dedupe and
 * normalization happen downstream.
 *
 * <p>Not a Spring bean: one instance per upload.
 */
public final class CsvComplaintSource implements ComplaintSource {

    /**
     * The CSV channel's {@code source_ref}. A constant, not a per-upload id, so re-uploading a file
     * matches the rows already held instead of duplicating them.
     */
    public static final String SOURCE_REF = "csv-upload";

    /** The header row is line 1, so the first data row is line 2. */
    private static final int FIRST_DATA_ROW_INDEX = 2;

    private final byte[] content;
    private final ColumnMapping mapping;
    private final UploadLimits limits;

    public CsvComplaintSource(byte[] content, ColumnMapping mapping, UploadLimits limits) {
        this.content = Objects.requireNonNull(content, "content").clone();
        this.mapping = Objects.requireNonNull(mapping, "mapping");
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    @Override
    public String sourceRef() {
        return SOURCE_REF;
    }

    @Override
    public SourceType type() {
        return SourceType.CSV_UPLOAD;
    }

    @Override
    public List<RawRow> fetch(FetchWindow window) {
        Objects.requireNonNull(window, "window");

        CsvReader.CsvContent csv = CsvReader.read(new String(content, StandardCharsets.UTF_8), limits);

        // Headers are resolved once, before any row is produced: an unresolvable required column
        // throws here and the run fails having created nothing (section 5.2).
        ResolvedColumns columns = mapping.resolve(csv.headers());

        List<RawRow> rows = new ArrayList<>(csv.rows().size());
        for (int offset = 0; offset < csv.rows().size(); offset++) {
            int rowIndex = FIRST_DATA_ROW_INDEX + offset;
            if (rowIndex < window.fromRowIndex()) {
                continue;
            }
            List<String> cells = csv.rows().get(offset);
            rows.add(new RawRow(rowIndex, csv.headers(), cells, columns.extract(cells)));
        }
        return rows;
    }

    /** Never the cells; rows hold student details. */
    @Override
    public String toString() {
        return "CsvComplaintSource[sourceRef=" + SOURCE_REF + ", bytes=" + content.length + "]";
    }
}
