package duty.ingestion.domain;

/**
 * An uploaded file was refused by the safety checks of section 11.6 — wrong media type, over 5 MB,
 * or over 5000 rows.
 *
 * <p>The media type and size checks run <em>before</em> the bytes are parsed, so a hostile or
 * merely enormous file never reaches the CSV reader at all.
 */
public class UploadRejectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public UploadRejectedException(String reason) {
        super(reason);
    }
}
