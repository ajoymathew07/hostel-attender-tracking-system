package duty.workflow.domain;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The initial SLA window per domain and priority — section 8.2's table, in working hours (OQ-2).
 *
 * <p>Section 4.5 states the same figures more coarsely ("Electrical 4h · Other 8h" for {@code P1}),
 * and section 8.2 is the one that spells out every cell, so this holds section 8.2's numbers: the
 * breach metric of section 8 and the due date on the attender's card must not be able to disagree
 * about what "late" means. Both sections mark the figures OQ-2 and configuration rather than
 * constants, so the values themselves live in YAML and this holds only the lookup.
 *
 * <p>{@link ComplaintDomain#UNCLASSIFIED} has no row, which is why the answer is an
 * {@link Optional}: a complaint nobody has placed yet has no target to be late against, and it
 * cannot be worked on either — it is in the triage queue waiting for a manager. It gets its due
 * date when it gets its domain.
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
