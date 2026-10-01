package in.ac.iitm.hostel.duty.dispatch.domain;

import java.time.Instant;

/**
 * One attender who passed the eligibility filter of section 6.2 stage 1, with everything the score
 * and the tie-break chain need to read.
 *
 * <p>Gathered by the application layer and handed in, so the scoring itself stays a pure function
 * of its inputs — which is what makes section 12's "an overloaded primary loses to an idle
 * secondary" expressible as a test with no database in it.
 *
 * @param coverage their coverage of the complaint's floor, or {@code null} if they cover it not at
 *     all
 * @param floorDistance floors to the nearest floor they do cover, or {@code null} if they cover no
 *     floor; zero whenever {@code coverage} is set
 * @param weightedLoad {@code L(a)} — 3 per P1, 2 per P2, 1 per P3 over their active tasks
 * @param assignedToday the anti-starvation tie-break
 * @param lastAssignedAt {@code null} if they have never held an assignment
 */
public record DispatchCandidate(
        long attenderId,
        Coverage coverage,
        Integer floorDistance,
        int weightedLoad,
        long assignedToday,
        Instant lastAssignedAt) {}
