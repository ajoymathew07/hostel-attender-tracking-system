package duty.ingestion.domain;

/** An upload refused by the limits: not a CSV, over 5 MB, or over 5000 rows. */
public class UploadRejectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public UploadRejectedException(String reason) {
        super(reason);
    }
}
