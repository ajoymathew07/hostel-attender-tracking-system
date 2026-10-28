package duty.analytics.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Where a complaint's time went, read from its audited status changes (section 8.1).
 *
 * <p>The time in a state is the gap between entering it and leaving it. {@code ON_HOLD} belongs to no
 * stage, so waiting on the Engineering Unit is never counted against anyone (T12).
 */
public final class Lifecycle {

    /** The section 8.1 stages, in pipeline order. */
    public static final List<String> STAGES =
            List.of("Dispatch", "Pickup", "Inspection", "Filing", "Repair", "Closure");

    private static final Map<String, String> STAGE_OF =
            Map.of(
                    "NEW", "Dispatch",
                    "TRIAGE", "Dispatch",
                    "ASSIGNED", "Pickup",
                    "INSPECTING", "Inspection",
                    "AWAITING_CMS", "Filing",
                    "IN_PROGRESS", "Repair",
                    "RESOLVED", "Closure");

    /** Complaint audit rows also record priority and domain changes; only status changes count here. */
    private static final Set<String> STATUSES =
            Set.of(
                    "NEW", "TRIAGE", "ASSIGNED", "INSPECTING", "AWAITING_CMS", "IN_PROGRESS", "ON_HOLD",
                    "RESOLVED", "CLOSED", "REJECTED");

    private Lifecycle() {}

    /** One audited move, oldest first. */
    public record Transition(String from, String to, Instant at) {}

    /** Hours spent in each stage. A stay that has not ended yet is not counted. */
    public static Map<String, Double> hoursByStage(Instant reportedAt, List<Transition> transitions) {
        Map<String, Double> hours = new LinkedHashMap<>();
        Instant enteredAt = reportedAt;
        for (Transition move : transitions) {
            if (!STATUSES.contains(move.to())) {
                continue;
            }
            String stage = STAGE_OF.get(move.from());
            if (stage != null) {
                hours.merge(stage, Duration.between(enteredAt, move.at()).toSeconds() / 3600.0, Double::sum);
            }
            enteredAt = move.at();
        }
        return hours;
    }

    /**
     * Resolution time (section 8.2): report to {@code RESOLVED}, hold time excluded. Empty for a complaint
     * that has not been resolved.
     */
    public static Optional<Double> resolutionHours(Instant reportedAt, List<Transition> transitions) {
        if (transitions.stream().noneMatch(move -> "RESOLVED".equals(move.to()))) {
            return Optional.empty();
        }
        return Optional.of(
                hoursByStage(reportedAt, transitions).entrySet().stream()
                        .filter(stage -> !"Closure".equals(stage.getKey()))
                        .mapToDouble(Map.Entry::getValue)
                        .sum());
    }
}
