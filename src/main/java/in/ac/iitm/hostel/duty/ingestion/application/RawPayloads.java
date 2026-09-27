package in.ac.iitm.hostel.duty.ingestion.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.ac.iitm.hostel.duty.ingestion.domain.RawRow;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Serialises a raw row for {@code source_row.raw_payload} (section 3.5).
 *
 * <p>Built with Jackson rather than by concatenation, for the same reason audit detail is: a cell
 * holding a quote would otherwise produce a payload that is not JSON, or worse, is JSON saying
 * something the resident wrote rather than what they answered.
 *
 * <p>The payload does hold the student's details, which is intended — it is the record of what the
 * source said, and it is purged after 90 days while the hash that dedupes it survives (section
 * 3.8). It is stored and never logged.
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
