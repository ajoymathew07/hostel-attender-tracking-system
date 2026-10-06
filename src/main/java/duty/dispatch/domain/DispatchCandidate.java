package duty.dispatch.domain;

import java.time.Instant;

/**
 * One eligible attender, with everything the score and tie-breaks need.
 *
 * @param coverage their coverage of the complaint's floor, or {@code null} if none
 * @param floorDistance floors to the nearest floor they cover, or {@code null} if they cover none
 * @param weightedLoad 3 per P1, 2 per P2, 1 per P3 over their active tasks
 * @param lastAssignedAt {@code null} if they have never been assigned
 */
public record DispatchCandidate(
        long attenderId,
        Coverage coverage,
        Integer floorDistance,
        int weightedLoad,
        long assignedToday,
        Instant lastAssignedAt) {}
