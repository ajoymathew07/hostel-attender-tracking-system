package duty.dispatch.domain;

import java.util.List;

/**
 * Who dispatch chose and why; stored as {@code task_assignment.score_snapshot}.
 *
 * <p>Runners-up are kept because a score means little without the others to compare against.
 *
 * @param tieBreak {@code null} when the winner's score was highest outright
 * @param capacityExceeded everyone was full; the work was assigned anyway
 * @param uncoveredFloor nobody eligible covers the floor, so the choice was made on load alone
 */
public record DispatchDecision(
        long attenderId,
        double score,
        double floorComponent,
        double loadComponent,
        List<RunnerUp> runnersUp,
        TieBreak tieBreak,
        boolean capacityExceeded,
        boolean uncoveredFloor) {

    /** An attender who scored and lost. */
    public record RunnerUp(long attenderId, double score) {}
}
