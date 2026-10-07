package duty.ingestion.domain;

import duty.ingestion.domain.ColumnMapping.SourceField;
import duty.ingestion.domain.ComplaintSource.RawRow;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
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

    /**
     * A raw row turned into complaint fields.
     *
     * @param rawText the description, exactly as typed
     * @param reportedAt the form timestamp, read as Asia/Kolkata time and stored as UTC
     * @param reporterRoom the room number, or {@code null} (the complaint then goes to triage)
     * @param reporterContact the resident's email; purged after 90 days
     * @param reportedDomain the category chosen on the form, as text; workflow interprets it
     */
    public record NormalizedRow(
            String rawText,
            Instant reportedAt,
            String reporterRoom,
            String reporterName,
            String reporterRollNo,
            String reporterPhone,
            String reporterContact,
            String reportedDomain) {

        /** Leaves out the student's details so they cannot reach a log. */
        @Override
        public String toString() {
            return "NormalizedRow[reportedAt=" + reportedAt + ", room=" + (reporterRoom == null ? "none" : "known") + "]";
        }
    }

    /**
     * One row could not become a complaint. Only that row is skipped; the run continues.
     *
     * <p>The message has the row number and field name only, never the cell value (it may hold student
     * data).
     */
    public static class MalformedRowException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final int rowIndex;

        public MalformedRowException(int rowIndex, String reason) {
            super("row " + rowIndex + ": " + reason);
            this.rowIndex = rowIndex;
        }

        public int getRowIndex() {
            return rowIndex;
        }
    }

    /**
     * Computes a row's identity and its change-detection hash.
     *
     * <pre>
     * response_key = SHA256( normalize(timestamp) || "\x1f" || normalize(email) )
     * content_hash = SHA256( join("\x1f", [normalize(cell) for cell in row]) )
     * </pre>
     *
     * <p>{@code response_key} does not depend on row position, so deleting a sheet row creates no
     * duplicates. {@code content_hash} changes when any answer is edited.
     */
    public static final class RowDigest {

        /**
         * ASCII unit separator: a form answer cannot contain it, so cell boundaries cannot be faked.
         */
        private static final String UNIT_SEPARATOR = "\u001f";

        private RowDigest() {}

        /**
         * Ignores missing values, extra whitespace and Unicode compatibility forms. Case is kept, because a
         * capitalisation fix is a real edit.
         */
        public static String normalize(String value) {
            if (value == null) {
                return "";
            }
            return Normalizer.normalize(value, Normalizer.Form.NFKC).trim().replaceAll("\\s+", " ");
        }

        /**
         * The stable identity of one response. The email is lower-cased since addresses are
         * case-insensitive.
         *
         * @param ordinal 0 normally; higher for repeat submissions by the same person in the same second
         */
        public static String responseKey(String timestamp, String email, int ordinal) {
            String material =
                    normalize(timestamp) + UNIT_SEPARATOR + normalize(email).toLowerCase(Locale.ROOT);
            if (ordinal > 0) {
                material = material + UNIT_SEPARATOR + ordinal;
            }
            return sha256(material);
        }

        /** Changes whenever any answer changes, and only then. */
        public static String contentHash(List<String> cells) {
            StringBuilder material = new StringBuilder();
            for (int i = 0; i < cells.size(); i++) {
                if (i > 0) {
                    material.append(UNIT_SEPARATOR);
                }
                material.append(normalize(cells.get(i)));
            }
            return sha256(material.toString());
        }

        private static String sha256(String material) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                return HexFormat.of().formatHex(digest.digest(material.getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException impossible) {
                // SHA-256 is required of every Java platform; there is no recovery and no fallback
                // worth having, because a weaker digest would silently weaken the idempotency key.
                throw new IllegalStateException("SHA-256 is unavailable", impossible);
            }
        }
    }
}
