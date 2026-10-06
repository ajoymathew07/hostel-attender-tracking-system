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
 * Normalization of a source row into complaint fields (section 5.4).
 *
 * <p>Shared by every {@link ComplaintSource}: the CSV fallback and the Sheets poller normalize
 * identically, because they are the same path (section 5.8).
 *
 * <p>Everything here fails <strong>per row</strong>. A response with an unreadable timestamp is
 * recorded and skipped, and the run continues over the rows that are fine — partial progress is
 * strictly better than all-or-nothing over data we do not control.
 */
public final class RowNormalizer {

    /**
     * Section 5.4's room pattern, applied to the upper-cased cell so {@code a-214} and {@code
     * A-214} both match.
     *
     * <p>Cauvery room numbers are digits only — 100–199 and 1000–1099 on the ground floor, 2xx and
     * 2xxx on the first, 3xx and 3xxx on the second. The hostel has wings, but the room number does
     * not name them, so there is no letter prefix to match. The design's pattern allows up to two
     * leading letters separated by an optional space; over the description, which section 5.4 makes
     * the fallback, that makes "no water in 1108" yield the room "IN 1108", and that string then
     * goes to dispatch. Matching digits only removes the problem at its root rather than patching
     * the separator.
     */
    private static final Pattern ROOM = Pattern.compile("\\b(\\d{3,4})\\b");

    /** Business dates are Asia/Kolkata; storage is UTC (NFR-7). */
    public static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Kolkata");

    /**
     * The shapes a Google Form timestamp arrives in. Locale decides which one a given sheet emits,
     * so the list is configuration rather than a constant.
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
     * The form timestamp, interpreted in the configured zone and returned as UTC.
     *
     * <p>The failure message names the field and the row and quotes <em>nothing</em>: a timestamp
     * cell is usually harmless, but the same message shape is what would be reused for other
     * fields, and a cell can hold a student's details (section 11.4).
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
     * The room number from the room cell, falling back to the description (section 5.4).
     *
     * <p>The fallback earns its place: residents routinely leave the room question blank and write
     * "no water in 1108" instead, and a complaint with no room cannot be dispatched to a floor.
     * When neither yields a room the answer is {@code null} rather than a guess, which routes the
     * complaint to triage where a human decides.
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

    /**
     * Digits only, keeping a leading {@code +} so a country code survives.
     *
     * <p>Residents type spaces, hyphens and brackets; an attender needs something dialable.
     */
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
