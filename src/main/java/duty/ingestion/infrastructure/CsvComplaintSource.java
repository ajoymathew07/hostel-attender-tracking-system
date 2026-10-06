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
 * Risk 1's Plan B: a manager-uploaded CSV, read through the one {@link ComplaintSource} port
 * (section 5.8).
 *
 * <p>This class does one job — turn bytes into raw rows — and deliberately knows nothing about
 * response keys, complaints or the database. Dedupe, normalization and categorization happen
 * downstream, shared verbatim with the Sheets adapter of PR 8. That is what makes re-uploading the
 * same CSV twice produce zero duplicates: the fallback inherits the guarantee rather than
 * reimplementing it.
 *
 * <p>Not a Spring bean. One instance wraps one uploaded file, so it is constructed per upload by
 * the ingestion service.
 */
public final class CsvComplaintSource implements ComplaintSource {

    /**
     * The CSV channel's identity within {@code uq_source_row}, and a constant on purpose.
     *
     * <p>Section 3.5 describes {@code source_ref} as "sheet id, or upload batch id". A literal
     * per-upload batch id would break the guarantee section 5.8 states outright: the same response
     * would get a different {@code (source_type, source_ref, response_key)} on every upload, and
     * re-uploading a file — the obvious thing a flustered user does during an outage — would
     * duplicate every complaint in it. One stable ref for the channel is what makes
     * {@code uq_source_row} bite. Raised in the PR notes.
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

    /** Never the cells — a row holds the student's details (section 11.4). */
    @Override
    public String toString() {
        return "CsvComplaintSource[sourceRef=" + SOURCE_REF + ", bytes=" + content.length + "]";
    }
}
