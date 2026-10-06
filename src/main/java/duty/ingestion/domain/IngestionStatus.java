package duty.ingestion.domain;

/** Lifecycle of one ingestion run (data model section 3.5). */
public enum IngestionStatus {
    RUNNING,
    SUCCESS,
    FAILED
}
