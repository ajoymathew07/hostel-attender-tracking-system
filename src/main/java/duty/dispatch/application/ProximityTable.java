package duty.dispatch.application;

/**
 * The floor-proximity values, for a screen to show as the fixed thing they are (section 6.2).
 *
 * <p>Published so the settings screen can explain what it is <em>not</em> letting the manager
 * edit: only the order of these values means anything, and an adjacent floor scoring above the
 * primary would be a scorer nobody could defend.
 */
public record ProximityTable(
        double primary, double secondary, double adjacent, double step, double minimum) {}
