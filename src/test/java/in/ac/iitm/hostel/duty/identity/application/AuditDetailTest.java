package in.ac.iitm.hostel.duty.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The escaping half of "the audit log may not be made to lie".
 *
 * <p>Validation stops a hostile address reaching {@code detail} today; escaping is what keeps that
 * true when a later caller records something that was never validated — a display name, a reason, a
 * value from an import.
 */
class AuditDetailTest {

    private static JsonNode parse(String json) throws Exception {
        return new ObjectMapper().readTree(json);
    }

    @Test
    @DisplayName("a value carrying JSON punctuation reads back as itself, not as extra fields")
    void escapesJsonPunctuation() throws Exception {
        String hostile = "a\",\"to\":\"attacker@evil.example.com";

        JsonNode detail = parse(AuditDetail.of("from", "victim@example.com", "to", hostile));

        assertThat(detail.size()).describedAs("two fields, not three").isEqualTo(2);
        assertThat(detail.get("to").asText()).isEqualTo(hostile);
        assertThat(detail.get("from").asText()).isEqualTo("victim@example.com");
    }

    @Test
    @DisplayName("backslashes and control characters survive a round trip")
    void escapesBackslashesAndControlCharacters() throws Exception {
        String awkward = "a\\b\u0001c\nd\"e";

        assertThat(parse(AuditDetail.of("email", awkward)).get("email").asText()).isEqualTo(awkward);
    }

    @Test
    @DisplayName("a null value is JSON null rather than the four letters n-u-l-l")
    void writesNullAsNull() throws Exception {
        JsonNode detail = parse(AuditDetail.of("email", null));

        assertThat(detail.get("email").isNull()).isTrue();
    }

    @Test
    @DisplayName("a literal is written unquoted, so `active` stays a boolean")
    void writesALiteralUnquoted() throws Exception {
        assertThat(parse(AuditDetail.ofLiteral("active", "false")).get("active").isBoolean()).isTrue();
        assertThat(parse(AuditDetail.ofLiteral("active", "true")).get("active").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("an odd number of arguments is a programming error, caught at the call")
    void rejectsUnpairedArguments() {
        assertThatThrownBy(() -> AuditDetail.of("email"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
