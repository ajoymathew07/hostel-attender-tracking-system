package duty.dispatch.application;

import duty.dispatch.domain.DispatchPolicy;
import duty.dispatch.domain.FloorProximity;
import duty.settings.application.Settings;
import duty.settings.application.SettingsStore;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * The dispatch weights currently in force: the YAML defaults, overridden by the settings screen.
 *
 * <p>Read on every dispatch, so a change applies to the next complaint without a restart. The
 * proximity table is not editable because only the order of its values matters.
 */
@Service
public class DispatchSettings {

    public static final String FLOOR_WEIGHT = "dispatch.weights.floor";
    public static final String LOAD_WEIGHT = "dispatch.weights.load";
    public static final String LOAD_CAP = "dispatch.load-cap";

    private final DispatchPolicy defaults;
    private final SettingsStore store;

    DispatchSettings(DispatchPolicy defaults, SettingsStore store) {
        this.defaults = defaults;
        this.store = store;
    }

    /** The three editable numbers, for the settings screen. */
    public DispatchWeights current() {
        DispatchPolicy policy = from(store.current());
        return new DispatchWeights(policy.floorWeight(), policy.loadWeight(), policy.loadCap());
    }

    /** The full scorer, for the dispatcher. */
    DispatchPolicy policy() {
        return from(store.current());
    }

    DispatchPolicy from(Settings settings) {
        return new DispatchPolicy(
                settings.number(FLOOR_WEIGHT, defaults.floorWeight()),
                settings.number(LOAD_WEIGHT, defaults.loadWeight()),
                settings.integer(LOAD_CAP, defaults.loadCap()),
                defaults.proximity());
    }

    /**
     * Validates new weights and returns them as settings rows. Refuses weights that do not sum to 1
     * rather than rescaling them.
     *
     * <p>Returns rows instead of saving them so the settings screen can save all sections in one
     * transaction.
     */
    public Map<String, String> edits(double floorWeight, double loadWeight, int loadCap) {
        if (Math.abs(floorWeight + loadWeight - 1) > 1e-9) {
            throw new IllegalArgumentException(
                    "the floor and load weights must sum to 1, but sum to " + (floorWeight + loadWeight));
        }
        if (loadCap < 1) {
            throw new IllegalArgumentException("the load cap must be at least 1");
        }
        return Map.of(
                FLOOR_WEIGHT, String.valueOf(floorWeight),
                LOAD_WEIGHT, String.valueOf(loadWeight),
                LOAD_CAP, String.valueOf(loadCap));
    }

    /** The fixed proximity table, for the settings screen to display. */
    public ProximityTable proximity() {
        FloorProximity table = defaults.proximity();
        return new ProximityTable(
                table.primary(), table.secondary(), table.adjacent(), table.step(), table.minimum());
    }
}
