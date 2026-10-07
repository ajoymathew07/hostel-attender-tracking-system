package duty.ingestion.infrastructure;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.SheetsScopes;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import duty.ingestion.domain.ColumnMapping.ResolvedColumns;
import duty.ingestion.domain.ColumnMapping;
import duty.ingestion.domain.ComplaintSource.FetchWindow;
import duty.ingestion.domain.ComplaintSource.RawRow;
import duty.ingestion.domain.ComplaintSource;
import duty.ingestion.domain.SourceRow.SourceType;
import duty.ingestion.infrastructure.IngestionConfiguration.SheetsProperties;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

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

    /**
     * Reads the cells of a sheet tab.
     *
     * <p>An interface so tests can substitute a fake instead of calling Google. One read-only method,
     * matching the read-only service account.
     */
    public interface SheetReader {

        /**
         * Reads a block of cells in A1 notation. Rows may be shorter than the header, because the API omits
         * trailing empty cells.
         *
         * @throws SheetsUnavailableException if the sheet cannot be read
         */
        List<List<Object>> readValues(String spreadsheetId, String range);
    }

    /**
     * {@link SheetReader} using the real Google Sheets API.
     *
     * <p>The client is built on first use, not at startup, so the app still starts without a credential
     * (as on a developer machine or in CI). The scope is read-only.
     */
    @Component
    static class GoogleApiSheetReader implements SheetReader {

        /** Sent as the User-Agent. */
        private static final String APPLICATION_NAME = "duty-tracker";

        private final SheetsProperties properties;

        /** Built on first read. Volatile because the poller and "Sync Now" run on different threads. */
        private volatile Sheets client;

        GoogleApiSheetReader(SheetsProperties properties) {
            this.properties = properties;
        }

        @Override
        public List<List<Object>> readValues(String spreadsheetId, String range) {
            try {
                List<List<Object>> values =
                        client().spreadsheets().values().get(spreadsheetId, range).execute().getValues();
                // An empty tab comes back with no `values` member at all rather than an empty list.
                return values == null ? List.of() : values;
            } catch (Exception failed) {
                throw new SheetsUnavailableException(spreadsheetId, failed);
            }
        }

        private Sheets client() throws Exception {
            Sheets built = client;
            if (built == null) {
                built = build();
                client = built;
            }
            return built;
        }

        private Sheets build() throws Exception {
            byte[] json = Base64.getDecoder().decode(Objects.requireNonNull(properties.getCredentials()));
            GoogleCredentials credentials =
                    GoogleCredentials.fromStream(new ByteArrayInputStream(json))
                            .createScoped(SheetsScopes.SPREADSHEETS_READONLY);
            return new Sheets.Builder(
                            GoogleNetHttpTransport.newTrustedTransport(),
                            GsonFactory.getDefaultInstance(),
                            new HttpCredentialsAdapter(credentials))
                    .setApplicationName(APPLICATION_NAME)
                    .build();
        }
    }

    /**
     * The sheet could not be read (no credential, no network, or the API refused). Fails the run, and the
     * reason is shown to the manager. Never includes the credential.
     */
    public static class SheetsUnavailableException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public SheetsUnavailableException(String spreadsheetId, Throwable cause) {
            super(
                    "the response sheet "
                            + spreadsheetId
                            + " could not be read ("
                            + cause.getClass().getSimpleName()
                            + ")",
                    cause);
        }
    }
}
