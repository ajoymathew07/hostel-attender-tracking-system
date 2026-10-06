package duty.settings.web;

import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The {@code /settings} form fields.
 *
 * <p>SLA windows are in hours, as the manager types them; they are converted to durations when saved.
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
