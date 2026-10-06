package duty.settings.application;

import java.time.Duration;
import java.time.LocalTime;
import java.util.Map;

/**
 * The overrides in force, read once and asked several questions.
 *
 * <p>A snapshot rather than a live lookup, because the dispatcher reads a dozen keys to score one
 * complaint and they must all come from the same moment: half a saved change applied and half not
 * is a score nobody could reproduce.
 *
 * <p>Every accessor takes the YAML value as its fallback, so a key nobody has overridden reads
 * exactly as {@code application.yml} says it does.
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
