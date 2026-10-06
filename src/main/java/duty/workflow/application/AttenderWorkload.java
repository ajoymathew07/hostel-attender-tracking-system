package duty.workflow.application;

import java.time.Instant;

/**
 * How busy one attender is: the inputs to the dispatch load score and tie-breaks.
 *
 * @param weighted 3 per P1, 2 per P2, 1 per P3 over their active tasks
 * @param assignedToday assignments given today, in the local timezone
 * @param lastAssignedAt {@code null} if never assigned
 */
public record AttenderWorkload(int weighted, long assignedToday, Instant lastAssignedAt) {}
