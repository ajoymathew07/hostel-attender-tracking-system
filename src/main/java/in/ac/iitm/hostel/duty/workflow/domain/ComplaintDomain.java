package in.ac.iitm.hostel.duty.workflow.domain;

/**
 * The maintenance domain a complaint belongs to, matching {@code ck_domain} (data model section
 * 3.5).
 *
 * <p>{@link #UNCLASSIFIED} is a real outcome, not a failure: a low-confidence classification is
 * routed to triage rather than silently becoming a wrong domain (test plan E1, E13, E14).
 */
public enum ComplaintDomain {
    ELECTRICAL,
    CIVIL,
    RO_PLANT,
    NETWORK,
    UNCLASSIFIED;

    /** Whether dispatch may act on this, or the complaint must go to triage (transition T2/T3). */
    public boolean isDispatchable() {
        return this != UNCLASSIFIED;
    }
}
