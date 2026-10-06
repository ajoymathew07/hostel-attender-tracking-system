package duty.ingestion.infrastructure;

/**
 * The sheet could not be read (no credential, no network, or the API refused). Fails the run, and the
 * reason is shown to the manager. Never includes the credential.
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
