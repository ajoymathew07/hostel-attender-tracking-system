package duty.workflow.domain;

/**
 * The <em>Actor</em> column of the transition table (section 4.2), which is the authorization half
 * of section 11.3 expressed as data.
 *
 * <p>Role alone is never enough for a task action: "any attender" would let one attender resolve
 * another's work and collect the +2. So the attender rows name the <em>assigned</em> attender, and
 * the check is an {@code ACTIVE} assignment, not a role.
 */
public enum TransitionActor {

    /** A scheduled job or the dispatcher; there is no principal behind it. */
    SYSTEM,

    /** Classify, assign, reassign, reject, close, reopen — the manager column of section 11.3. */
    MANAGER,

    /** The attender holding an {@code ACTIVE} assignment for this complaint's task, and only them. */
    ASSIGNED_ATTENDER,

    /** Either of the two: T12 and T13 name both. */
    ATTENDER_OR_MANAGER
}
