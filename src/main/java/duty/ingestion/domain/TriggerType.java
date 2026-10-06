package duty.ingestion.domain;

/** What started an ingestion run. */
public enum TriggerType {

    /** The periodic poll, serialised across instances by a database lock. */
    SCHEDULED,

    /** The manager's "Sync Now" button, or the Apps Script trigger waking the instance. */
    MANUAL,

    /** Catch-up on application start, so a wake after sleep loses nothing. */
    STARTUP
}
