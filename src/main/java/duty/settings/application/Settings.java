package duty.settings.application;

import java.time.Duration;
import java.time.LocalTime;
import java.util.Map;

/**
 * A snapshot of the overrides in force. Every accessor falls back to the YAML value.
 *
 * <p>A snapshot so that all values used for one decision come from the same moment.
 */
public record Settings(Map<String, String> values) {

    public String text(String name, String fallback) {
        return values.getOrDefault(name, fallback);
    }

    public double number(String name, double fallback) {
        String value = values.get(name);
        return value == null ? fallback : Double.parseDouble(value);
    }

    public int integer(String name, int fallback) {
        String value = values.get(name);
        return value == null ? fallback : Integer.parseInt(value);
    }

    /** ISO-8601, the same shape the YAML uses, so a copied value means the same thing. */
    public Duration duration(String name, Duration fallback) {
        String value = values.get(name);
        return value == null ? fallback : Duration.parse(value);
    }

    public LocalTime time(String name, LocalTime fallback) {
        String value = values.get(name);
        return value == null ? fallback : LocalTime.parse(value);
    }
}
