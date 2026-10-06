package duty.ingestion.application;

/** What happened to one source row. */
public enum RowOutcome {

    /** New response: a source row and a complaint were created. */
    CREATED,

    /** Already held (or an edited response): skipped. */
    SKIPPED
}
