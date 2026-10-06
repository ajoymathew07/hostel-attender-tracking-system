package duty.workflow.application;

/**
 * What the attender is shown after tapping Resolve.
 *
 * @param pointsAwarded points credited by this call; {@code 0} on a repeated tap
 * @param periodTotal their total for the month afterwards
 * @param alreadyApplied {@code true} on a repeated tap; the screen looks the same either way
 */
public record ResolvedTask(
        String publicRef, int pointsAwarded, long periodTotal, boolean alreadyApplied) {}
