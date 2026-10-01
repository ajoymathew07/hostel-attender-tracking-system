package in.ac.iitm.hostel.duty.workflow.domain;

/**
 * The maintenance domain a complaint belongs to, matching {@code ck_domain} (data model section
 * 3.5).
 *
 * <p>The resident picks one on the form. {@link #UNCLASSIFIED} is a real outcome and not a
 * failure: it is what "Others" means, and what an unanswered question means, and either way the
 * complaint belongs in the triage queue rather than silently becoming a wrong domain (test plan
 * E1, E13, E14).
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

    /**
     * The domain a resident chose on the form, as one of these.
     *
     * <p>Matched leniently — case and surrounding space are the resident's typing, not their
     * meaning, and the Form's own wording is not ours to control (OQ-4). {@code LAN} is accepted
     * for {@link #NETWORK} and {@code RO} for {@link #RO_PLANT} because those are what the
     * question offers; the enum names are the database's vocabulary, not the form's.
     *
     * <p>Anything else — {@code Others}, a blank, a value somebody added to the Form without
     * telling us — is {@link #UNCLASSIFIED}. A changed Form therefore fills the triage queue
     * rather than mislabelling complaints or stopping the ingestion run.
     */
    public static ComplaintDomain reported(String answer) {
        if (answer == null) {
            return UNCLASSIFIED;
        }
        return switch (answer.trim().toUpperCase().replace(' ', '_')) {
            case "ELECTRICAL", "ELECTRIC" -> ELECTRICAL;
            case "CIVIL" -> CIVIL;
            case "RO", "RO_PLANT", "RO_WATER", "WATER" -> RO_PLANT;
            case "LAN", "NETWORK", "INTERNET" -> NETWORK;
            default -> UNCLASSIFIED;
        };
    }
}
