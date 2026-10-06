package duty.settings.web;

import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Everything the {@code /settings} screen can change, in the shapes a form field actually has.
 *
 * <p>SLA windows are <strong>hours</strong> here rather than ISO-8601 durations. A manager setting
 * "electrical P1" types 4, not {@code PT4H}; the conversion belongs at the edge where the typing
 * happens, not in the service that stores it.
 *
 * <p>The windows are a map keyed {@code DOMAIN.PRIORITY} rather than twelve named fields, because
 * twelve named fields is twelve places to forget when a domain is added.
 */
public class SettingsForm {

    private double floorWeight;
    private double loadWeight;
    private int loadCap;

    private Map<String, Double> slaHours = new LinkedHashMap<>();
    private LocalTime dayStart;
    private LocalTime dayEnd;

    public double getFloorWeight() {
        return floorWeight;
    }

    public void setFloorWeight(double floorWeight) {
        this.floorWeight = floorWeight;
    }

    public double getLoadWeight() {
        return loadWeight;
    }

    public void setLoadWeight(double loadWeight) {
        this.loadWeight = loadWeight;
    }

    public int getLoadCap() {
        return loadCap;
    }

    public void setLoadCap(int loadCap) {
        this.loadCap = loadCap;
    }

    public Map<String, Double> getSlaHours() {
        return slaHours;
    }

    public void setSlaHours(Map<String, Double> slaHours) {
        this.slaHours = slaHours;
    }

    public LocalTime getDayStart() {
        return dayStart;
    }

    public void setDayStart(LocalTime dayStart) {
        this.dayStart = dayStart;
    }

    public LocalTime getDayEnd() {
        return dayEnd;
    }

    public void setDayEnd(LocalTime dayEnd) {
        this.dayEnd = dayEnd;
    }
}
