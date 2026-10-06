package duty.workflow.domain;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The SLA window per domain and priority, in working hours. The values come from YAML.
 *
 * <p>{@link ComplaintDomain#UNCLASSIFIED} has no window, hence {@link Optional}: it gets a due date
 * once a manager sets its domain.
 */
public record SlaTargets(Map<ComplaintDomain, Map<Priority, Duration>> windows) {

    public SlaTargets {
        windows = Map.copyOf(Objects.requireNonNull(windows, "windows"));
    }

    /** The working-hours window for this pair, or empty if the domain has no target. */
    public Optional<Duration> window(ComplaintDomain domain, Priority priority) {
        return Optional.ofNullable(windows.get(domain)).map(byPriority -> byPriority.get(priority));
    }
}
