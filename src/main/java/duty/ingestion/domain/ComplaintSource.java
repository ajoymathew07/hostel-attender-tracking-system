package duty.ingestion.domain;

import java.util.List;

/**
 * Where complaints come from — the ingestion module's one port (sections 2.2, 5.8).
 *
 * <p>Complaints arrive two ways: polled from the Google Sheet, or uploaded as a CSV when the Sheets
 * API is unavailable. Different sources, but both produce the same thing — a list of raw rows. So
 * there is one interface that says exactly that, and the two ways of fetching rows are two
 * implementations of it. Everything downstream — deduplication, normalization, categorization,
 * dispatch — is written once, against this interface, and never learns which source the rows came
 * from.
 *
 * <p>The alternative is what usually happens without this boundary: the CSV path grows its own copy
 * of the dedupe and normalization logic, a fix lands in one copy and not the other, and the two
 * quietly disagree. This interface is what makes the fallback path <em>the same path</em> — which
 * is also why re-uploading the same CSV twice cannot create duplicates. It inherits the guarantee
 * rather than reimplementing it.
 *
 * <p>Two implementations: {@code CsvComplaintSource} and {@code GoogleSheetsSource}.
 */
public interface ComplaintSource {

    /**
     * Identifies the source within its type: a sheet id, or the CSV channel.
     *
     * <p>This is half of {@code uq_source_row}, so it must be <strong>stable across
     * uploads</strong>. A value that varied per upload — a batch id, a filename, a timestamp —
     * would give the same response a different identity each time and re-uploading a CSV would
     * duplicate every complaint in it.
     */
    String sourceRef();

    /** Which kind of source this is, the other half of {@code uq_source_row}. */
    SourceType type();

    /**
     * Reads rows from the source.
     *
     * @throws MissingRequiredColumnException if a required column cannot be resolved from the
     *     headers — the run fails, and nothing is ingested
     */
    List<RawRow> fetch(FetchWindow window);
}
