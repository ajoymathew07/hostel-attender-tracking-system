package in.ac.iitm.hostel.duty.workflow.domain;

/**
 * One row of section 4.2's transition table.
 *
 * <p>The <em>Side effects</em> column is not here. A side effect writes to the ledger, the
 * assignment and the audit log, so it needs repositories, and the domain layer may not have them
 * (architecture section 2.4, rule 1). What belongs here is the part that is genuinely data: which
 * move is legal, who may make it and what must hold first.
 */
public record ComplaintTransition(
        ComplaintStatus from,
        WorkflowEvent event,
        ComplaintStatus to,
        TransitionActor actor,
        TransitionGuard guard) {}
