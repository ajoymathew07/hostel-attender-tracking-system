package duty.dispatch.application;

import duty.dispatch.domain.DispatchPolicy;
import duty.dispatch.domain.FloorProximity;
import duty.settings.application.Settings;
import duty.settings.application.SettingsStore;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * The dispatch heuristic's weights, as the manager currently has them — section 6.2's R5,
 * "tunable without a redeploy".
 *
 * <p>The YAML policy is the default and an {@code app_setting} row overrides it. Read on every
 * dispatch rather than held, so a change on the settings screen takes effect on the next complaint
 * instead of the next restart — which is the whole of what R5 asks for.
 *
 * <p>The proximity table is deliberately not editable here. It is five numbers whose
 * <em>relative</em> order is what the heuristic means by "closer", and a screen that let a manager
 * set the adjacent floor above the primary would produce a scorer nobody could explain. The
 * weights and the cap are the knobs section 6.2 says the manager turns.
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

    /** The three editable numbers, as the settings screen reads them. */
    public DispatchWeights current() {
        DispatchPolicy policy = from(store.current());
        return new DispatchWeights(policy.floorWeight(), policy.loadWeight(), policy.loadCap());
    }

    /** The whole scorer, for the dispatcher — which is inside this module and may see it. */
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
     * Validates a new set of weights and returns them as settings rows, refusing a pair that does
     * not sum to 1.
     *
     * <p>Refused rather than normalised: a manager who typed 0.7 and 0.4 meant something, and
     * silently rescaling both to 0.64 and 0.36 would give them a scorer they did not ask for.
     *
     * <p>Returning the rows rather than writing them is what lets the settings screen save every
     * section in one transaction: a value this module refuses must not leave another module's
     * values already changed.
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

    /** The proximity table, for the settings screen to show as the fixed thing it is. */
    public ProximityTable proximity() {
        FloorProximity table = defaults.proximity();
        return new ProximityTable(
                table.primary(), table.secondary(), table.adjacent(), table.step(), table.minimum());
    }
}
