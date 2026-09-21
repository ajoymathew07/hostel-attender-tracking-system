package in.ac.iitm.hostel.duty.identity.application;

/**
 * Builds the JSON of an {@code audit_event.detail} from values that came from outside.
 *
 * <p>{@code detail} is a {@code TEXT} column, so the database will not reject malformed JSON — a
 * value carrying a quote produces a row that still inserts and still <em>looks</em> like a record
 * while saying something other than what happened. Since the audit log is the evidence base when a
 * bonus or an allowlist change is disputed (section 11.7), every value goes through here rather
 * than being concatenated at the call site.
 */
final class AuditDetail {

    private AuditDetail() {}

    /** A JSON object of alternating keys and values, each value escaped. */
    static String of(String... keysAndValues) {
        if (keysAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("keys and values must pair up");
        }
        StringBuilder detail = new StringBuilder("{");
        for (int i = 0; i < keysAndValues.length; i += 2) {
            if (i > 0) {
                detail.append(',');
            }
            detail.append('"').append(escape(keysAndValues[i])).append("\":");
            String value = keysAndValues[i + 1];
            if (value == null) {
                detail.append("null");
            } else {
                detail.append('"').append(escape(value)).append('"');
            }
        }
        return detail.append('}').toString();
    }

    /** A JSON object with one literal (unquoted) value, for {@code true} / {@code false}. */
    static String ofLiteral(String key, String literal) {
        return "{\"" + escape(key) + "\":" + literal + "}";
    }

    static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') {
                escaped.append('\\').append(c);
            } else if (c < 0x20) {
                escaped.append(String.format("\\u%04x", (int) c));
            } else {
                escaped.append(c);
            }
        }
        return escaped.toString();
    }
}
