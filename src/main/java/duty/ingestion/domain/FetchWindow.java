package duty.ingestion.domain;

/**
 * How much of a source to read (section 5.3).
 *
 * <p>For Sheets this is {@code max(1, lastRowSeen − OVERLAP)}: the overlap absorbs row-index drift
 * if somebody deletes a row, and {@code response_key} makes re-reading rows we already hold a
 * no-op. A long gap is the same code path with a larger window, which is why there is no separate
 * catch-up mode to get wrong.
 *
 * <p>An uploaded CSV is a whole file with no watermark, so {@link #all()} is what the CSV adapter
 * is handed. The parameter exists on the port because the <em>Sheets</em> adapter needs it, and
 * one port is the point (section 5.8).
 *
 * @param fromRowIndex the first row to read, 1-based and inclusive of the header row
 */
public record FetchWindow(int fromRowIndex) {

    public FetchWindow {
        if (fromRowIndex < 1) {
            throw new IllegalArgumentException("fromRowIndex is 1-based; got " + fromRowIndex);
        }
    }

    /** Everything the source holds. */
    public static FetchWindow all() {
        return new FetchWindow(1);
    }
}
