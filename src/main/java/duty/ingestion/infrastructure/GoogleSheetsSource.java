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
 * The Google Form response sheet as a {@link ComplaintSource}. It only turns cells into rows; dedupe
 * and normalization happen downstream.
 *
 * <p>Not a Spring bean: one instance per run.
 */
public final class GoogleSheetsSource implements ComplaintSource {

    /**
     * Rows re-read before the last row seen, to absorb rows deleted from the sheet. Re-reading held rows
     * is harmless.
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
     * Where to start: {@code max(1, lastRowSeen − OVERLAP)}. After a long sleep this is the same code with
     * a bigger window.
     *
     * @param lastRowSeen the highest row already held, or {@code null} on the first run
     */
    public static FetchWindow windowFrom(Integer lastRowSeen) {
        return lastRowSeen == null
                ? FetchWindow.all()
                : new FetchWindow(Math.max(1, lastRowSeen - OVERLAP));
    }

    /** The sheet id. */
    @Override
    public String sourceRef() {
        return spreadsheetId;
    }

    @Override
    public SourceType type() {
        return SourceType.GOOGLE_SHEETS;
    }

    /**
     * Reads the whole tab in one API call, then keeps rows inside the window. The header row is needed on
     * every run, and the quota limits requests, not size.
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

    /** Never the cells; rows hold student details. */
    @Override
    public String toString() {
        return "GoogleSheetsSource[spreadsheetId=" + spreadsheetId + ", sheetName=" + sheetName + "]";
    }
}
