package in.ac.iitm.hostel.duty.audit.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Audit detail is serialised, never concatenated (section 11.7).
 *
 * <p>The test that matters is the injection one. If a value can close its own string and open a
 * second key, then the audit log records what the logged value says rather than what happened, and
 * the log stops being evidence.
 */
class AuditDetailTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode parse(String json) throws Exception {
        return JSON.readTree(json);
    }

    @Test
    @DisplayName("an ordinary detail is a JSON object with the keys it was given")
    void buildsAJsonObject() throws Exception {
        JsonNode detail = parse(AuditDetail.of("publicRef", "CAU-2026-0417").and("rowsCreated", 3).json());

        assertThat(detail.get("publicRef").asText()).isEqualTo("CAU-2026-0417");
        assertThat(detail.get("rowsCreated").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("a value that tries to open a second key cannot — it stays one escaped value")
    void aHostileValueCannotForgeAKey() throws Exception {
        String hostile = "ravi\",\"role\":\"MANAGER";

        JsonNode detail = parse(AuditDetail.of("email", hostile).json());

        assertThat(detail.get("email").asText()).isEqualTo(hostile);
        assertThat(detail.has("role")).isFalse();
        assertThat(detail.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("backslashes, newlines and control characters survive as data, not as syntax")
    void escapingCoversMoreThanQuotes() throws Exception {
        String awkward = "a\\b\nc\td\u0007";

        assertThat(parse(AuditDetail.of("value", awkward).json()).get("value").asText()).isEqualTo(awkward);
    }

    @Test
    @DisplayName("nulls, numbers and booleans keep their JSON types")
    void typesAreKept() throws Exception {
        JsonNode detail =
                parse(AuditDetail.of("reason", null).and("rows", 12).and("failed", true).json());

        assertThat(detail.get("reason").isNull()).isTrue();
        assertThat(detail.get("rows").isNumber()).isTrue();
        assertThat(detail.get("failed").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("keys keep their insertion order, so a detail reads the way it was written")
    void keysKeepTheirOrder() {
        assertThat(AuditDetail.of("first", 1).and("second", 2).toString())
                .isEqualTo("{\"first\":1,\"second\":2}");
    }
}
