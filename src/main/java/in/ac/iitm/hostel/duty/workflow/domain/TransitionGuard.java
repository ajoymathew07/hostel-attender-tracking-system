package in.ac.iitm.hostel.duty.workflow.domain;

/**
 * The <em>Guard</em> column of the transition table (section 4.2), as a name plus the sentence a
 * human is told when it refuses.
 *
 * <p>The guard is named here and <em>evaluated</em> in {@code WorkflowService}, because deciding
 * "does this attender hold an ACTIVE assignment" needs a repository and the domain layer may not
 * have one (architecture section 2.4, rule 1). Keeping the name in the table is what lets the
 * table be read line by line against section 4.2.
 */
public enum TransitionGuard {

    /** No guard: the row in section 4.2 has a dash. */
    NONE("no guard"),

    /** T2 — domain is classified and dispatch returned somebody. */
    DISPATCH_CANDIDATE("no attender was available for this complaint"),

    /** T3 — the complement of T2, which is what sends a complaint to triage. */
    NO_DISPATCH_CANDIDATE("dispatch found a candidate, so this complaint does not need triage"),

    /** T4 — there is somebody to hand it to. */
    TARGET_ATTENDER("an attender to assign the work to is required"),

    /** T6 — a reassignment that does not move the work is not a reassignment. */
    DIFFERENT_ATTENDER("this attender already holds the work"),

    /** T5, T8, T10, T12, T17 — "Reason mandatory", five times over. */
    REASON("a reason is required"),

    /** T11 — there is no CMS API (section 1.8), so the format is all that can be checked. */
    DOCKET_FORMAT("the docket number does not match the configured pattern"),

    /** T14 — the docket gate: no docket, no points. */
    DOCKET_FILED("this complaint was never filed with the Engineering Unit"),

    /** T16 — auto-close fires seven days after resolution, not before. */
    SEVEN_DAYS_SINCE_RESOLUTION("seven days have not passed since this was resolved");

    private final String reason;

    TransitionGuard(String reason) {
        this.reason = reason;
    }

    /** Why the transition was refused, in words a manager or attender can act on (section 11.6). */
    public String reason() {
        return reason;
    }
}
