package duty.ingestion.domain;

/** Where a row came from. */
public enum SourceType {

    /** The Google Form response sheet. */
    GOOGLE_SHEETS,

    /** A CSV uploaded by the manager. */
    CSV_UPLOAD
}
