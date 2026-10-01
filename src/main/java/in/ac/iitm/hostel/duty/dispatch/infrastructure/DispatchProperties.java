package in.ac.iitm.hostel.duty.dispatch.infrastructure;

import in.ac.iitm.hostel.duty.dispatch.domain.DispatchPolicy;
import in.ac.iitm.hostel.duty.dispatch.domain.FloorProximity;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The heuristic's weights and its proximity table, as configuration (section 6.2, R5).
 *
 * <p>Section 6.2 requires these be tunable without a redeploy, and section 6.2's own table says
 * the manager edits them from the configuration screen. Properties, not constants, for the same
 * reason the SLA targets are.
 */
@ConfigurationProperties(prefix = "dispatch")
public class DispatchProperties {

    /** The wing every Cauvery room belongs to; room numbers do not carry one (section 5.4). */
    private String wing = "CAUVERY";

    private final Weights weights = new Weights();
    private final Proximity proximity = new Proximity();

    /** {@code L_cap} — the weighted load at which an attender counts as full. */
    private int loadCap = 8;

    /** A weighted sum whose weights do not sum to 1 is not a score in [0, 1] (section 6.2). */
    public DispatchPolicy toPolicy() {
        double total = weights.floor + weights.load;
        if (Math.abs(total - 1) > 1e-9) {
            throw new IllegalStateException("dispatch.weights must sum to 1, but sum to " + total);
        }
        return new DispatchPolicy(
                weights.floor,
                weights.load,
                loadCap,
                new FloorProximity(
                        proximity.primary,
                        proximity.secondary,
                        proximity.adjacent,
                        proximity.step,
                        proximity.minimum));
    }

    public String getWing() {
        return wing;
    }

    public void setWing(String wing) {
        this.wing = wing;
    }

    public int getLoadCap() {
        return loadCap;
    }

    public void setLoadCap(int loadCap) {
        this.loadCap = loadCap;
    }

    public Weights getWeights() {
        return weights;
    }

    public Proximity getProximity() {
        return proximity;
    }

    /** {@code w_f} and {@code w_l} of section 6.2. */
    public static class Weights {

        private double floor = 0.60;
        private double load = 0.40;

        public double getFloor() {
            return floor;
        }

        public void setFloor(double floor) {
            this.floor = floor;
        }

        public double getLoad() {
            return load;
        }

        public void setLoad(double load) {
            this.load = load;
        }
    }

    /** Section 6.2's floor-proximity table. */
    public static class Proximity {

        private double primary = 1.00;
        private double secondary = 0.70;
        private double adjacent = 0.40;
        private double step = 0.05;
        private double minimum = 0.10;

        public double getPrimary() {
            return primary;
        }

        public void setPrimary(double primary) {
            this.primary = primary;
        }

        public double getSecondary() {
            return secondary;
        }

        public void setSecondary(double secondary) {
            this.secondary = secondary;
        }

        public double getAdjacent() {
            return adjacent;
        }

        public void setAdjacent(double adjacent) {
            this.adjacent = adjacent;
        }

        public double getStep() {
            return step;
        }

        public void setStep(double step) {
            this.step = step;
        }

        public double getMinimum() {
            return minimum;
        }

        public void setMinimum(double minimum) {
            this.minimum = minimum;
        }
    }
}
