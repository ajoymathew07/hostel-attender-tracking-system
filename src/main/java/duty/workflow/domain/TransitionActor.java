package duty.workflow.domain;

/**
 * Who may make a transition.
 *
 * <p>Attender moves require holding an {@code ACTIVE} assignment, not just the attender role, so
 * nobody can act on someone else's task.
 */
public enum TransitionActor {

    /** The system (a scheduled job or the dispatcher). */
    SYSTEM,

    /** The manager. */
    MANAGER,

    /** The attender holding an {@code ACTIVE} assignment on this complaint's task. */
    ASSIGNED_ATTENDER,

    /** Either the assigned attender or the manager (block and unblock). */
    ATTENDER_OR_MANAGER
}
