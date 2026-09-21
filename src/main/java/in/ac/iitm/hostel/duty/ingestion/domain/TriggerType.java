package in.ac.iitm.hostel.duty.ingestion.domain;

/**
 * What caused an ingestion run (data model section 3.5).
 *
 * <p>Polling alone cannot be relied on, because a free-tier instance asleep has no scheduler — so
 * ingestion is event-driven with polling as a backstop (architecture section 2.5).
 */
public enum TriggerType {

    /** The periodic poll, serialised across instances by a database lock. */
    SCHEDULED,

    /** The manager's "Sync Now" button, or the Apps Script trigger waking the instance. */
    MANUAL,

    /** Catch-up on application start, so a wake after sleep loses nothing. */
    STARTUP
}
