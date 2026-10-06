package duty.workflow.application;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/**
 * The SLA table and the working day as a screen sees them (sections 4.5, 8.2, 10.4).
 *
 * <p>Domains and priorities are their names rather than the enums, and windows are hours rather
 * than durations, because {@code workflow.domain} is internal to this module (architecture section
 * 2.4, rule 3) and because a manager setting "electrical P1" types 4, not {@code PT4H}. The
 * conversion lives here, with the vocabulary it belongs to.
 *
 * @param cells {@code "ELECTRICAL.P1" → 4.0} — one entry per editable window
 */
public record SlaConfiguration(
        List<String> domains,
        List<String> priorities,
        Map<String, Double> cells,
        LocalTime dayStart,
        LocalTime dayEnd,
        String zone) {}
