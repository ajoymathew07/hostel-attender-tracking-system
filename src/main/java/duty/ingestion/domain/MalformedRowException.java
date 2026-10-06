package duty.ingestion.domain;

/**
 * One source row could not be normalized into a complaint (section 5.4).
 *
 * <p>Unlike {@link MissingRequiredColumnException} this is <em>per row</em>: each row is processed
 * in its own transaction, so one malformed response is recorded on {@code
 * ingestion_run.error_detail} and the run continues (section 5.3). Over data we do not control,
 * partial progress is strictly better than all-or-nothing.
 *
 * <p>The message carries the <strong>row number and the field name only</strong> — never the cell
 * value. A bad row is the most likely thing to end up quoted in a log or an error page, and the
 * cell may hold a student's name, roll number or phone number (section 11.4).
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
