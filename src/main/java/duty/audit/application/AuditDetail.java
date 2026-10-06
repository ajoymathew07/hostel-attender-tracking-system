package duty.audit.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Builds the JSON of {@link AuditEntry#detail()} by serialising, never by concatenating.
 *
 * <p>Why: with concatenation, a value containing a quote could inject extra keys and make the audit
 * log claim something that did not happen. Never put student data in here; audit rows are kept
 * forever.
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
