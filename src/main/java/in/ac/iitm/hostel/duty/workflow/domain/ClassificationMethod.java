package in.ac.iitm.hostel.duty.workflow.domain;

/**
 * How a complaint's domain was decided (data model section 3.5).
 *
 * <p>Stored alongside the raw text rather than in place of it, so a complaint filed under the
 * wrong heading is always recoverable: the manager can see what the resident actually wrote and
 * correct the domain from triage.
 */
public enum ClassificationMethod {

    /** The resident chose it on the form. */
    REPORTED,

    /** A manager set the domain from the triage queue. */
    MANUAL,

    /** The resident chose "Others", or chose nothing; the complaint went to triage. */
    FALLBACK
}
