package duty.ingestion.application;

import duty.ingestion.application.IngestionPipeline.IngestionSummary;
import duty.ingestion.domain.ColumnMapping;
import duty.ingestion.domain.ComplaintSource.FetchWindow;
import duty.ingestion.domain.ComplaintSource;
import duty.ingestion.domain.IngestionRun.TriggerType;
import duty.ingestion.domain.UploadLimits;
import duty.ingestion.infrastructure.CsvComplaintSource;
import java.util.Objects;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * The manager's CSV upload, the fallback when the Sheets API is unavailable.
 *
 * <p>It wraps the file as a {@link ComplaintSource} and hands it to {@link IngestionPipeline}, the same
 * path the Sheets poller uses, so re-uploading a file creates no duplicates.
 */
@Service
public class CsvUploadService {

    private final IngestionPipeline pipeline;
    private final ColumnMapping columnMapping;
    private final UploadLimits limits;

    CsvUploadService(
            IngestionPipeline pipeline, ColumnMapping columnMapping, UploadLimits limits) {
        this.pipeline = pipeline;
        this.columnMapping = columnMapping;
        this.limits = limits;
    }

    /** Ingests an uploaded CSV. Manager-only. */
    @PreAuthorize("hasRole('MANAGER')")
    public IngestionSummary upload(UploadedFile file) {
        // Section 11.6 — media type and size are decided before a single byte is parsed, and
        // before a run is even opened.
        limits.validate(file.filename(), file.contentType(), file.size());

        ComplaintSource source = new CsvComplaintSource(file.content(), columnMapping, limits);
        return pipeline.run(source, TriggerType.MANUAL, FetchWindow.all());
    }

    /** An uploaded file: name, media type and bytes, passed from the web layer to ingestion. */
    public record UploadedFile(String filename, String contentType, byte[] content) {

        public UploadedFile {
            Objects.requireNonNull(content, "content");
            content = content.clone();
        }

        public long size() {
            return content.length;
        }

        public byte[] content() {
            return content.clone();
        }

        /** Name and type only, never the contents, which hold student details. */
        @Override
        public String toString() {
            return "UploadedFile[filename=" + filename + ", contentType=" + contentType + ", bytes=" + content.length + "]";
        }
    }
}
