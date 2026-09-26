package in.ac.iitm.hostel.duty.ingestion.infrastructure;

/**
 * The response sheet could not be read — no credential, no network, or the API refused.
 *
 * <p>This fails the <strong>whole run</strong> and creates nothing, for the same reason a missing
 * required column does (section 5.2): silence is the failure mode to avoid. The reason lands on
 * {@code ingestion_run.error_detail}, so "the 06:03 poll could not reach the sheet" is a row the
 * manager can be shown rather than something they infer from complaints having stopped.
 *
 * <p>The message names the sheet and the cause's class, never the credential.
 */
public class SheetsUnavailableException extends RuntimeException {

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
