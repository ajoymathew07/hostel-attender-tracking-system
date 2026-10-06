package duty.ingestion.infrastructure;

import java.util.List;

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
