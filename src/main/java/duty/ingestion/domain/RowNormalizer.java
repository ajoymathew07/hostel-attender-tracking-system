package duty.ingestion.domain;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a raw row into complaint fields. Shared by both sources.
 *
 * <p>Failures are per row: a bad row is skipped and the run continues.
 */
public final class RowNormalizer {

    /**
     * A 3- or 4-digit room number. Digits only, because Cauvery room numbers have no letters; allowing
     * letters would read "no water in 1108" as room "IN 1108".
     */
    private static final Pattern ROOM = Pattern.compile("\\b(\\d{3,4})\\b");

    /** Form times are Asia/Kolkata; storage is UTC. */
    public static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Kolkata");

    /**
     * Timestamp formats a Google Form may use; it depends on the sheet's locale, so it is configurable.
     */
    public static final List<String> DEFAULT_TIMESTAMP_PATTERNS =
            List.of("yyyy/MM/dd HH:mm:ss", "yyyy-MM-dd HH:mm:ss", "dd/MM/yyyy HH:mm:ss", "yyyy/MM/dd HH:mm");

    private final List<DateTimeFormatter> timestampFormats;
    private final ZoneId zone;

    public RowNormalizer(List<String> timestampPatterns, ZoneId zone) {
        Objects.requireNonNull(timestampPatterns, "timestampPatterns");
        if (timestampPatterns.isEmpty()) {
            throw new IllegalArgumentException("at least one timestamp pattern must be configured");
        }
        List<DateTimeFormatter> formats = new ArrayList<>(timestampPatterns.size());
        for (String pattern : timestampPatterns) {
            formats.add(DateTimeFormatter.ofPattern(pattern, Locale.ROOT));
        }
        this.timestampFormats = List.copyOf(formats);
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    public static RowNormalizer defaults() {
        return new RowNormalizer(DEFAULT_TIMESTAMP_PATTERNS, DEFAULT_ZONE);
    }

    /** @throws MalformedRowException if the row cannot become a complaint */
    public NormalizedRow normalize(RawRow row) {
        Objects.requireNonNull(row, "row");

        String description = row.get(SourceField.DESCRIPTION);
        if (description == null || description.isBlank()) {
            throw new MalformedRowException(row.rowIndex(), "the description cell is blank");
        }

        return new NormalizedRow(
                description,
                parseTimestamp(row.rowIndex(), row.get(SourceField.TIMESTAMP)),
                room(row.get(SourceField.ROOM), description),
                trimmedOrNull(row.get(SourceField.NAME)),
                upperCasedOrNull(row.get(SourceField.ROLL_NO)),
                phoneOrNull(row.get(SourceField.PHONE)),
                emailOrNull(row.get(SourceField.EMAIL)),
                trimmedOrNull(row.get(SourceField.CATEGORY)));
    }

    /**
     * The form timestamp, read in the configured zone and returned as UTC. The error message never quotes
     * the cell.
     */
    Instant parseTimestamp(int rowIndex, String cell) {
        String value = cell == null ? "" : cell.trim();
        if (value.isEmpty()) {
            throw new MalformedRowException(rowIndex, "the timestamp cell is blank");
        }
        for (DateTimeFormatter format : timestampFormats) {
            try {
                return LocalDateTime.parse(value, format).atZone(zone).toInstant();
            } catch (DateTimeParseException notThisFormat) {
                // Try the next configured shape; only exhausting them all is a malformed row.
            }
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException notAnInstant) {
            throw new MalformedRowException(rowIndex, "the timestamp could not be read in any configured format");
        }
    }

    /**
     * The room number from the room cell, falling back to the description ("no water in 1108"). Returns
     * {@code null} if neither has one, and the complaint goes to triage.
     */
    static String room(String roomCell, String description) {
        String fromCell = matchRoom(roomCell);
        return fromCell != null ? fromCell : matchRoom(description);
    }

    private static String matchRoom(String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = ROOM.matcher(text.toUpperCase(Locale.ROOT));
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private static String trimmedOrNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String upperCasedOrNull(String value) {
        String trimmed = trimmedOrNull(value);
        return trimmed == null ? null : trimmed.toUpperCase(Locale.ROOT);
    }

    private static String emailOrNull(String value) {
        String trimmed = trimmedOrNull(value);
        return trimmed == null ? null : trimmed.toLowerCase(Locale.ROOT);
    }

    /** Digits only, keeping a leading {@code +}, so the attender gets a dialable number. */
    private static String phoneOrNull(String value) {
        String trimmed = trimmedOrNull(value);
        if (trimmed == null) {
            return null;
        }
        boolean international = trimmed.startsWith("+");
        String digits = trimmed.replaceAll("\\D", "");
        if (digits.isEmpty()) {
            return null;
        }
        return international ? "+" + digits : digits;
    }
}
