package duty.ingestion.domain;

import java.util.Locale;
import java.util.Set;

/**
 * Upload limits: CSV only, at most 5 MB and 5000 rows.
 *
 * <p>Type and size are checked before parsing, so a huge or hostile file never reaches the parser. The
 * row limit is checked while reading.
 */
public final class UploadLimits {

    /** 5 MB. */
    public static final long DEFAULT_MAX_BYTES = 5L * 1024 * 1024;

    /** 5000 rows. */
    public static final int DEFAULT_MAX_ROWS = 5000;

    /**
     * Media types browsers send for a {@code .csv} file. {@code text/plain} and
     * {@code application/vnd.ms-excel} are accepted only with a {@code .csv} name.
     */
    private static final Set<String> ACCEPTED_CONTENT_TYPES =
            Set.of("text/csv", "application/csv", "text/plain", "application/vnd.ms-excel");

    private static final String CSV_EXTENSION = ".csv";

    private final long maxBytes;
    private final int maxRows;

    public UploadLimits(long maxBytes, int maxRows) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        if (maxRows <= 0) {
            throw new IllegalArgumentException("maxRows must be positive");
        }
        this.maxBytes = maxBytes;
        this.maxRows = maxRows;
    }

    public static UploadLimits defaults() {
        return new UploadLimits(DEFAULT_MAX_BYTES, DEFAULT_MAX_ROWS);
    }

    public long maxBytes() {
        return maxBytes;
    }

    public int maxRows() {
        return maxRows;
    }

    /**
     * Checks name, type and size before parsing.
     *
     * @throws UploadRejectedException if the file is not a CSV, is empty, or is too large
     */
    public void validate(String filename, String contentType, long sizeBytes) {
        if (filename == null || !filename.toLowerCase(Locale.ROOT).endsWith(CSV_EXTENSION)) {
            throw new UploadRejectedException("Only a CSV file can be uploaded — the file must be named .csv");
        }
        if (contentType == null || !ACCEPTED_CONTENT_TYPES.contains(mediaType(contentType))) {
            throw new UploadRejectedException(
                    "Only a CSV file can be uploaded — this one was sent as "
                            + (contentType == null ? "no content type at all" : mediaType(contentType)));
        }
        if (sizeBytes <= 0) {
            throw new UploadRejectedException("That file is empty");
        }
        if (sizeBytes > maxBytes) {
            throw new UploadRejectedException(
                    "That file is larger than the " + (maxBytes / (1024 * 1024)) + " MB upload limit");
        }
    }

    /** @throws UploadRejectedException if the file holds more rows than one upload may carry */
    public void requireWithinRowLimit(int rows) {
        if (rows > maxRows) {
            throw new UploadRejectedException(
                    "That file holds more than the " + maxRows + " rows one upload may carry");
        }
    }

    /** Strips {@code ; charset=UTF-8} and the like, which browsers append freely. */
    private static String mediaType(String contentType) {
        int parameters = contentType.indexOf(';');
        String bare = parameters < 0 ? contentType : contentType.substring(0, parameters);
        return bare.trim().toLowerCase(Locale.ROOT);
    }
}
