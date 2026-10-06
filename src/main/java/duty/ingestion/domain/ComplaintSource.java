package duty.ingestion.domain;

import java.util.List;

/**
 * Where complaint rows come from: the Google Sheet or an uploaded CSV.
 *
 * <p>Both produce the same raw rows, so everything downstream (dedupe, normalization, complaint
 * creation) is written once. That is why re-uploading a CSV creates no duplicates.
 */
public interface ComplaintSource {

    /**
     * Identifies the source within its type: the sheet id, or the CSV channel. Must be the same on every
     * upload, or re-uploading would duplicate every complaint.
     */
    String sourceRef();

    /** Which kind of source this is. */
    SourceType type();

    /**
     * Reads rows from the source.
     *
     * @throws MissingRequiredColumnException if a required column is missing; the run fails
     */
    List<RawRow> fetch(FetchWindow window);
}
