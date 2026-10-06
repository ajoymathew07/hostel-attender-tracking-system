package duty.workflow.domain;

/**
 * A transition's condition, with the message shown when it fails. Evaluated in
 * {@code WorkflowService}, since some checks need the database.
 */
public enum TransitionGuard {

    /** No condition. */
    NONE("no guard"),

    /** T2: an attender was found. */
    DISPATCH_CANDIDATE("no attender was available for this complaint"),

    /** T3: no attender was found. */
    NO_DISPATCH_CANDIDATE("dispatch found a candidate, so this complaint does not need triage"),

    /** T4: an attender is given. */
    TARGET_ATTENDER("an attender to assign the work to is required"),

    /** T6: a different attender from the current one. */
    DIFFERENT_ATTENDER("this attender already holds the work"),

    /** A reason is required. */
    REASON("a reason is required"),

    /** T11: the docket number matches the format. */
    DOCKET_FORMAT("the docket number does not match the configured pattern"),

    /** T14: a docket is filed. No docket, no points. */
    DOCKET_FILED("this complaint was never filed with the Engineering Unit"),

    /** T16: seven days have passed since resolution. */
    SEVEN_DAYS_SINCE_RESOLUTION("seven days have not passed since this was resolved");

    private final String reason;

    TransitionGuard(String reason) {
        this.reason = reason;
    }

    /** The message shown when this condition fails. */
    public String reason() {
        return reason;
    }
}
