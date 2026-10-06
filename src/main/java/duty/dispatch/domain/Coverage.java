package duty.dispatch.domain;

/** Matches {@code ck_coverage} (data model section 3.4). */
public enum Coverage {

    /** The attender dispatch prefers for this floor. */
    PRIMARY,

    /** Backup cover when the primary is unavailable or over capacity. */
    SECONDARY
}
