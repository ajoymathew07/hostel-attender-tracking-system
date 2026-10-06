package duty.dispatch.application;

/** The floor-proximity values, so the settings screen can show them (read-only). */
public record ProximityTable(
        double primary, double secondary, double adjacent, double step, double minimum) {}
