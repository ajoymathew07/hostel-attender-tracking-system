package in.ac.iitm.hostel.duty.audit.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Builds the JSON of {@link AuditEntry#detail()} — <strong>by serialising, never by
 * concatenating</strong>.
 *
 * <p>PR 3's review found audit detail being assembled with string concatenation. That is not a
 * style problem. A value containing a quote and a comma closes the string and opens a second key,
 * so a user-supplied field — an email address, a filename, a rejected header — can make the audit
 * log claim something other than what happened. The log is the evidence used to settle bonus
 * disputes and reconstruct history (section 11.7); a log that can be written by the thing it is
 * logging is worth nothing.
 *
 * <p>Jackson escapes every value, so the worst a hostile string can do is appear, verbatim and
 * harmless, inside one JSON value.
 *
 * <p>Detail is also the wrong place for student data. Nothing here should carry a name, roll
 * number or phone number (section 11.4) — audit rows are retained indefinitely, which is exactly
 * what the retention limits are for.
 */
public final class AuditDetail {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Map<String, Object> values = new LinkedHashMap<>();

    private AuditDetail() {}

    public static AuditDetail of(String key, Object value) {
        return new AuditDetail().and(key, value);
    }

    public AuditDetail and(String key, Object value) {
        values.put(Objects.requireNonNull(key, "key"), value);
        return this;
    }

    /** The detail as a JSON object, ready for {@link AuditEntry#detail()}. */
    public String json() {
        try {
            return JSON.writeValueAsString(values);
        } catch (JsonProcessingException impossible) {
            // Only strings, numbers, booleans and nulls are ever put in; none of them can fail.
            throw new IllegalStateException("audit detail could not be serialised", impossible);
        }
    }

    @Override
    public String toString() {
        return json();
    }
}
