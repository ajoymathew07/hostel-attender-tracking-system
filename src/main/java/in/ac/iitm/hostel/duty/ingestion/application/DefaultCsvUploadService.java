package in.ac.iitm.hostel.duty.ingestion.application;

import in.ac.iitm.hostel.duty.ingestion.domain.ColumnMapping;
import in.ac.iitm.hostel.duty.ingestion.domain.ComplaintSource;
import in.ac.iitm.hostel.duty.ingestion.domain.FetchWindow;
import in.ac.iitm.hostel.duty.ingestion.domain.TriggerType;
import in.ac.iitm.hostel.duty.ingestion.domain.UploadLimits;
import in.ac.iitm.hostel.duty.ingestion.infrastructure.CsvComplaintSource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * The CSV fallback — Risk 1's Plan B (section 5.8).
 *
 * <p>All this class does is turn an uploaded file into a {@link ComplaintSource} and hand it to
 * {@link IngestionPipeline}. The reconciliation, the dedupe and the complaint creation are the
 * pipeline's, shared verbatim with the Sheets adapter, which is what makes re-uploading the same
 * CSV twice produce zero duplicates: the fallback inherits the guarantee rather than
 * reimplementing it.
 */
@Service
class DefaultCsvUploadService implements CsvUploadService {

    private final IngestionPipeline pipeline;
    private final ColumnMapping columnMapping;
    private final UploadLimits limits;

    DefaultCsvUploadService(
            IngestionPipeline pipeline, ColumnMapping columnMapping, UploadLimits limits) {
        this.pipeline = pipeline;
        this.columnMapping = columnMapping;
        this.limits = limits;
    }

    /**
     * {@code @PreAuthorize} on the service is the control (section 11.3). The upload form is also
     * hidden from an attender, but hiding a form is decoration; this is the check, and it holds for
     * any future caller that reaches the service by another route.
     */
    @Override
    @PreAuthorize("hasRole('MANAGER')")
    public IngestionSummary upload(UploadedFile file) {
        // Section 11.6 — media type and size are decided before a single byte is parsed, and
        // before a run is even opened.
        limits.validate(file.filename(), file.contentType(), file.size());

        ComplaintSource source = new CsvComplaintSource(file.content(), columnMapping, limits);
        return pipeline.run(source, TriggerType.MANUAL, FetchWindow.all());
    }
}
