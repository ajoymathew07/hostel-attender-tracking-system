package duty.workflow.domain;

/** How a complaint's domain was decided. */
public enum ClassificationMethod {

    /** The resident chose it on the form. */
    REPORTED,

    /** A manager set the domain from the triage queue. */
    MANUAL,

    /** The resident chose "Others", or chose nothing; the complaint went to triage. */
    FALLBACK
}
