package duty.ingestion.infrastructure;

import duty.ingestion.domain.ColumnMapping;
import duty.ingestion.domain.ComplaintSource;
import duty.ingestion.domain.FetchWindow;
import duty.ingestion.domain.RawRow;
import duty.ingestion.domain.ResolvedColumns;
import duty.ingestion.domain.SourceType;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The Google Form response sheet, read through the one {@link ComplaintSource} port (sections 2.2,
 * 5.3).
 *
 * <p>Sibling of {@code CsvComplaintSource} and its equal: this class turns cells into raw rows and
 * knows nothing about response keys, complaints or the database. Dedupe, normalization and
 * complaint creation happen downstream and are shared verbatim between the two sources, which is
 * why polling the same sheet twice creates nothing the second time — the guarantee is inherited,
 * not reimplemented.
 *
 * <p>Not a Spring bean. One instance wraps one sheet for one run, so it is constructed per run by
 * the ingestion service, exactly as the CSV adapter is constructed per upload.
 */
public final class GoogleSheetsSource implements ComplaintSource {

    /**
     * Section 5.3's {@code OVERLAP}.
     *
     * <p>It absorbs row-index drift if somebody deletes a row from the sheet, and {@code
     * response_key} makes re-reading rows we already hold a no-op regardless. A constant rather
     * than a knob: it is a property of the algorithm, and there is no operational question a
     * different value would answer.
     */
    private static final int OVERLAP = 50;

    /** The header row is sheet row 1, so the first response is row 2. */
    private static final int HEADER_ROW_INDEX = 1;

    /** Wide enough for any Form; the API returns only the columns that exist. */
    private static final String COLUMNS = "!A:ZZ";

    private final SheetReader reader;
    private final String spreadsheetId;
    private final String sheetName;
    private final ColumnMapping mapping;

    public GoogleSheetsSource(
            SheetReader reader, String spreadsheetId, String sheetName, ColumnMapping mapping) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.spreadsheetId = Objects.requireNonNull(spreadsheetId, "spreadsheetId");
        this.sheetName = Objects.requireNonNull(sheetName, "sheetName");
        this.mapping = Objects.requireNonNull(mapping, "mapping");
    }

    /**
     * Section 5.3's scan strategy: {@code from = max(1, lastRowSeen − OVERLAP)}.
     *
     * <p>A long gap — the instance slept for six hours, or a manual "Sync Now" after a week — is
     * the same code path with a larger window, because {@code lastRowSeen} has not moved. There is
     * no separate catch-up mode to get wrong.
     *
     * @param lastRowSeen the highest sheet row already held, or {@code null} on a first run
     */
    public static FetchWindow windowFrom(Integer lastRowSeen) {
        return lastRowSeen == null
                ? FetchWindow.all()
                : new FetchWindow(Math.max(1, lastRowSeen - OVERLAP));
    }

    /** The sheet id, which is stable for the life of the sheet — half of {@code uq_source_row}. */
    @Override
    public String sourceRef() {
        return spreadsheetId;
    }

    @Override
    public SourceType type() {
        return SourceType.GOOGLE_SHEETS;
    }

    /**
     * One API call, always from row 1.
     *
     * <p>The window selects which rows are <em>considered</em>, not how much is asked for: headers
     * live in row 1 and must be resolved on every run, and the quota that matters is 300
     * <em>requests</em> per minute (section 5.3), which a narrower range would not improve. So the
     * range is the whole tab and the window is applied to the rows it returns.
     */
    @Override
    public List<RawRow> fetch(FetchWindow window) {
        Objects.requireNonNull(window, "window");

        List<List<Object>> values = reader.readValues(spreadsheetId, "'" + sheetName + "'" + COLUMNS);

        // Headers are resolved once, before any row is produced. An empty sheet has no header row
        // at all, so this is also where "the tab is wrong" surfaces — loudly, as a failed run with
        // nothing ingested (section 5.2).
        List<String> headers = cellsOf(values.isEmpty() ? List.of() : values.get(0));
        ResolvedColumns columns = mapping.resolve(headers);

        List<RawRow> rows = new ArrayList<>();
        for (int offset = 1; offset < values.size(); offset++) {
            int rowIndex = HEADER_ROW_INDEX + offset;
            if (rowIndex < window.fromRowIndex()) {
                continue;
            }
            List<String> cells = cellsOf(values.get(offset));
            // Spreadsheets routinely carry blank rows in the middle; each would otherwise become a
            // complaint with no text at all.
            if (cells.stream().allMatch(String::isBlank)) {
                continue;
            }
            rows.add(new RawRow(rowIndex, headers, cells, columns.extract(cells)));
        }
        return rows;
    }

    /** The API hands back {@code Object} and omits trailing empty cells; both are normal. */
    private static List<String> cellsOf(List<Object> row) {
        List<String> cells = new ArrayList<>(row.size());
        for (Object cell : row) {
            cells.add(cell == null ? "" : cell.toString());
        }
        return cells;
    }

    /** Never the cells — a row holds the student's details (section 11.4). */
    @Override
    public String toString() {
        return "GoogleSheetsSource[spreadsheetId=" + spreadsheetId + ", sheetName=" + sheetName + "]";
    }
}
