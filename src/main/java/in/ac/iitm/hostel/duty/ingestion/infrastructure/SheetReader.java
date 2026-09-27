package in.ac.iitm.hostel.duty.ingestion.infrastructure;

import java.util.List;

/**
 * The one call this system makes to the Sheets API: give me the cells of a tab.
 *
 * <p>It exists as an interface for one reason — it is the seam the tests substitute. The design is
 * explicit that we test <em>our</em> reconciliation and failure handling, not Google's delivery
 * (test plan section 12.1), and a test that reached the real API would need a credential, a
 * network and a sheet, and would still prove nothing about the code we wrote.
 *
 * <p>It is deliberately narrower than {@code Sheets}: one method, values only, no writes. The
 * service account has read-only access to the single response sheet (section 11.5), and this
 * signature says the same thing in Java.
 */
public interface SheetReader {

    /**
     * Reads a rectangular block of cells in A1 notation, top-left first.
     *
     * <p>Rows are returned exactly as the API gives them, which means trailing empty cells are
     * omitted and a row may be shorter than the header. That is normal, not an error: a resident
     * who answered no optional questions produces a short row.
     *
     * @throws SheetsUnavailableException if the sheet cannot be read
     */
    List<List<Object>> readValues(String spreadsheetId, String range);
}
