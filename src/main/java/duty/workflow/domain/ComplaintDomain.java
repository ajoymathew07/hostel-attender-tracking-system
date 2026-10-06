package duty.workflow.domain;

/**
 * The maintenance domain of a complaint. Matches {@code ck_domain}.
 *
 * <p>{@link #UNCLASSIFIED} means "Others" or no answer; such complaints go to triage.
 */
public enum ComplaintDomain {
    ELECTRICAL,
    CIVIL,
    RO_PLANT,
    NETWORK,
    UNCLASSIFIED;

    /** Whether dispatch can act on it, or it must go to triage. */
    public boolean isDispatchable() {
        return this != UNCLASSIFIED;
    }

    /**
     * The domain for the category a resident chose. Ignores case and spaces; accepts {@code LAN} for
     * {@link #NETWORK} and {@code RO} for {@link #RO_PLANT}. Anything else is {@link #UNCLASSIFIED}, so a
     * changed Form fills the triage queue instead of mislabelling complaints.
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
