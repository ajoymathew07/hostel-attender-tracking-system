package in.ac.iitm.hostel.duty.dispatch.domain;

import java.util.List;

/**
 * Who dispatch chose and why — the content of {@code task_assignment.score_snapshot} (section
 * 6.2, R3).
 *
 * <p>The runners-up are kept because a score on its own is not an explanation: "Ravi scored 0.78"
 * answers nothing until you can see that Kumar scored 0.50.
 *
 * @param tieBreak {@code null} when the winning score stood on its own
 * @param capacityExceeded every eligible attender was at or over {@code L_cap}; the work was
 *     assigned anyway, because it cannot simply vanish (section 6.4)
 * @param uncoveredFloor nobody eligible covers the complaint's floor, so every floor term was the
 *     minimum and the choice fell to load alone
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
