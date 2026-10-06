package duty.ingestion.domain;

/**
 * Which rows of a source to read.
 *
 * <p>For Sheets: from the last row seen minus an overlap, which absorbs deleted rows. A CSV is read
 * whole ({@link #all()}).
 *
 * @param fromRowIndex the first row to read, 1-based (row 1 is the header)
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
