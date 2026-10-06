package duty.workflow.application;

/**
 * What the attender is told after tapping <em>Resolve</em> (section 9.3).
 *
 * @param pointsAwarded what this call credited — {@code 0} on a replay, because the credit was
 *     already there
 * @param periodTotal their standing for the current period afterwards
 * @param alreadyApplied {@code true} on a replay. The attender sees the same success screen either
 *     way; the flag exists for diagnostics, not for the user (section 7.4).
 */
public record ResolvedTask(
        String publicRef, int pointsAwarded, long periodTotal, boolean alreadyApplied) {}
