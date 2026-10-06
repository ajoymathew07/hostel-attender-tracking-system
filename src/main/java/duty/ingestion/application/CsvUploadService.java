package duty.ingestion.application;

/**
 * The manager's CSV upload — Risk 1's Plan B (section 5.8), and the ingestion module's published
 * surface for it.
 */
public interface CsvUploadService {

    /**
     * Validates, reads and ingests an uploaded CSV, recording the run either way.
     *
     * <p>Manager-only, enforced on the implementation (section 11.3).
     *
     * @throws duty.ingestion.domain.UploadRejectedException if the file fails the
     *     media-type or size checks of section 11.6, which run before it is parsed
     */
    IngestionSummary upload(UploadedFile file);
}
