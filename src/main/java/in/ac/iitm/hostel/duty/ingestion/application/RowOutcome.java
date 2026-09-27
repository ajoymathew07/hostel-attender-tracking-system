package in.ac.iitm.hostel.duty.ingestion.application;

/** What happened to one source row (the reconciliation rules of section 5.3). */
public enum RowOutcome {

    /** The response key was unseen: a source row and a complaint were created. */
    CREATED,

    /** Already ingested, or an edited response — skipped, with {@code last_seen} touched. */
    SKIPPED
}
