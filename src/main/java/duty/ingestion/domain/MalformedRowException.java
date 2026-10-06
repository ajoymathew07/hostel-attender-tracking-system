package duty.ingestion.domain;

/**
 * One row could not become a complaint. Only that row is skipped; the run continues.
 *
 * <p>The message has the row number and field name only, never the cell value (it may hold student
 * data).
 */
public class MalformedRowException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int rowIndex;

    public MalformedRowException(int rowIndex, String reason) {
        super("row " + rowIndex + ": " + reason);
        this.rowIndex = rowIndex;
    }

    public int getRowIndex() {
        return rowIndex;
    }
}
