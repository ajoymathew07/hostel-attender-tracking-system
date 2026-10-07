package duty.dispatch.infrastructure;

import duty.dispatch.domain.DispatchPolicy.FloorProximity;
import duty.dispatch.domain.DispatchPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The default dispatch weights and proximity table from {@code application.yml}. */
@ConfigurationProperties(prefix = "dispatch")
public class DispatchProperties {

    /** Room numbers carry no wing, so every room is placed in this one. */
    private String wing = "CAUVERY";

    private final Weights weights = new Weights();
    private final Proximity proximity = new Proximity();

    /** The weighted load at which an attender counts as full. */
    private int loadCap = 8;

    /** Builds the policy; refuses weights that do not sum to 1. */
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

    /** The floor and load weights. */
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

    /** The floor-proximity table. */
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

    /**
     * Creates the {@link DispatchPolicy} bean from YAML. Domain classes cannot carry Spring annotations,
     * so the bean is declared here.
     */
    @Configuration
    @EnableConfigurationProperties(DispatchProperties.class)
    static class DispatchConfiguration {

        @Bean
        DispatchPolicy dispatchPolicy(DispatchProperties properties) {
            return properties.toPolicy();
        }
    }
}
