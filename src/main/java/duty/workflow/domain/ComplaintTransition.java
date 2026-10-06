package duty.workflow.domain;

/**
 * One row of the transition table: from-state, event, to-state, who may do it, and its condition.
 * Side effects live in {@code WorkflowService}, since they need repositories.
 */
public record ComplaintTransition(
        ComplaintStatus from,
        WorkflowEvent event,
        ComplaintStatus to,
        TransitionActor actor,
        TransitionGuard guard) {}
