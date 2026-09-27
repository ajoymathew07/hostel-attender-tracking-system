package in.ac.iitm.hostel.duty.categorization.application;

import java.math.BigDecimal;

/**
 * What the lexicon made of one complaint (section 5.5).
 *
 * <p>{@code domain} is a plain {@code String} and not the workflow module's {@code ComplaintDomain}
 * for the same reason {@code category_term.domain} is: the complaint vocabulary belongs to
 * workflow, and no module may reach into another's domain package (architecture section 2.4,
 * rule 3). The caller maps the name.
 *
 * <p>The confidence is reported whether or not the gate accepted it, because section 12.3's
 * accuracy-and-coverage measurement needs the number for the rejected items too.
 *
 * @param domain the accepted domain's name, or {@code null} — which means triage, not failure
 * @param confidence 0.000 to 1.000, as {@code complaint.domain_confidence} stores it
 */
public record Classification(String domain, BigDecimal confidence) {

    /** The gate was cleared: this domain may be assigned automatically. */
    public static Classification of(String domain, BigDecimal confidence) {
        return new Classification(domain, confidence);
    }

    /**
     * Nothing cleared the gate.
     *
     * <p>Section 5.5's deliberate precision-over-recall stance: a wrongly auto-assigned complaint
     * sends an attender to the wrong job and erodes trust in the whole system, whereas a triaged
     * one costs the manager three seconds.
     */
    public static Classification unclassified(BigDecimal confidence) {
        return new Classification(null, confidence);
    }
}
