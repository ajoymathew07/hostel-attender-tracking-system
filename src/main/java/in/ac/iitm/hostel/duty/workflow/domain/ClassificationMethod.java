package in.ac.iitm.hostel.duty.workflow.domain;

/**
 * How a complaint's domain was decided (data model section 3.5).
 *
 * <p>Stored alongside the raw text and a confidence score, never in place of them, so a
 * misclassification is always recoverable and the gold-set evaluation can be re-run over real data
 * (test plan section 12.3).
 */
public enum ClassificationMethod {

    /** The weighted lexicon cleared its confidence gate. */
    RULES,

    /** A manager set the domain from the triage queue. */
    MANUAL,

    /** Nothing cleared the gate; the complaint went to triage as UNCLASSIFIED. */
    FALLBACK
}
