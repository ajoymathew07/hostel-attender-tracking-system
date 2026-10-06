package duty.workflow.application;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/**
 * The SLA table and working day as the settings screen shows them, in names and hours.
 *
 * @param cells e.g. {@code "ELECTRICAL.P1" → 4.0}
 */
public record SlaConfiguration(
        List<String> domains,
        List<String> priorities,
        Map<String, Double> cells,
        LocalTime dayStart,
        LocalTime dayEnd,
        String zone) {}
