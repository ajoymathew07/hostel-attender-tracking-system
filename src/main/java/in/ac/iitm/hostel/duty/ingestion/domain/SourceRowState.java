package in.ac.iitm.hostel.duty.ingestion.domain;

/** Matches {@code ck_sr_state} (data model section 3.5). */
public enum SourceRowState {

    /** The row as last observed in the source. */
    ACTIVE,

    /**
     * Reserved. Amendment handling — a sheet row edited after ingestion — is backlog (section 13.8,
     * test plan E10); until then an edited row is skipped and logged.
     */
    AMENDED
}
