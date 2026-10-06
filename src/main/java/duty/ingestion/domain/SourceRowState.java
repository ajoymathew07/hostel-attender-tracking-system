package duty.ingestion.domain;

/** Matches {@code ck_sr_state}. */
public enum SourceRowState {

    /** The row as last observed in the source. */
    ACTIVE,

    /**
     * Reserved for edited responses, which are not handled yet; an edited row is currently skipped.
     */
    AMENDED
}
