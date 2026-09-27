package in.ac.iitm.hostel.duty.dispatch.domain;

/** Matches {@code ck_avail_status} (data model section 3.4). */
public enum AvailabilityStatus {

    /** Eligible for dispatch. */
    AVAILABLE,

    /** On leave; dispatch skips them and records the reason (test plan E6). */
    ON_LEAVE,

    /** Outside their duty hours. */
    OFF_DUTY;

    public boolean eligibleForDispatch() {
        return this == AVAILABLE;
    }
}
