package duty.ingestion.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import duty.ingestion.domain.RawRow;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Serialises a raw row to JSON for {@code source_row.raw_payload}.
 *
 * <p>Built with Jackson so a quote in a cell cannot break the JSON. The payload holds student details,
 * so it is purged after 90 days and never logged.
 */
@Component
class RawPayloads {

    private final ObjectMapper json = new ObjectMapper();

    public String toJson(RawRow row) {
        Map<String, String> payload = new LinkedHashMap<>();
        List<String> headers = row.headers();
        List<String> cells = row.cells();
        for (int index = 0; index < headers.size(); index++) {
            payload.put(headers.get(index), index < cells.size() ? cells.get(index) : "");
        }
        try {
            return json.writeValueAsString(payload);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("row " + row.rowIndex() + " could not be serialised", impossible);
        }
    }
}
