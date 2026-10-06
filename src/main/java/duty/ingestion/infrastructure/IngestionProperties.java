package duty.ingestion.infrastructure;

import duty.ingestion.domain.ColumnMapping;
import duty.ingestion.domain.RowNormalizer;
import duty.ingestion.domain.SourceField;
import duty.ingestion.domain.UploadLimits;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Ingestion settings from {@code application.yml}: column mapping, required fields, timezone, upload
 * limits.
 *
 * <p>The mapping is under {@code ingestion.columns}, not {@code ingestion.sheets}, because the CSV is a
 * download of the same sheet and has the same headers.
 */
@ConfigurationProperties(prefix = "ingestion")
public class IngestionProperties {

    /** Accepted header names per field, keyed by {@link SourceField#configKey()}. */
    private Map<String, List<String>> columns = new LinkedHashMap<>();

    /** Fields whose absence fails the whole run. */
    private List<String> required = new ArrayList<>();

    private List<String> timestampPatterns = new ArrayList<>(RowNormalizer.DEFAULT_TIMESTAMP_PATTERNS);

    /** Zone of the form timestamps; storage is UTC. */
    private String zone = RowNormalizer.DEFAULT_ZONE.getId();

    private final Upload upload = new Upload();

    public ColumnMapping toColumnMapping() {
        Map<SourceField, List<String>> aliases = new EnumMap<>(SourceField.class);
        columns.forEach(
                (key, names) ->
                        aliases.put(
                                field(key, "ingestion.columns"),
                                names == null ? List.of() : List.copyOf(names)));

        Set<SourceField> requiredFields = EnumSet.noneOf(SourceField.class);
        for (String key : required) {
            requiredFields.add(field(key, "ingestion.required"));
        }
        return new ColumnMapping(aliases, requiredFields);
    }

    public UploadLimits toUploadLimits() {
        return new UploadLimits(upload.getMaxBytes(), upload.getMaxRows());
    }

    public RowNormalizer toRowNormalizer() {
        return new RowNormalizer(timestampPatterns, ZoneId.of(zone));
    }

    /** An unknown field name fails startup, rather than silently mapping nothing. */
    private static SourceField field(String key, String where) {
        return SourceField.byConfigKey(key)
                .orElseThrow(
                        () ->
                                new IllegalArgumentException(
                                        where + " names an unknown field '" + key + "'"));
    }

    public Map<String, List<String>> getColumns() {
        return columns;
    }

    public void setColumns(Map<String, List<String>> columns) {
        this.columns = columns;
    }

    public List<String> getRequired() {
        return required;
    }

    public void setRequired(List<String> required) {
        this.required = required;
    }

    public List<String> getTimestampPatterns() {
        return timestampPatterns;
    }

    public void setTimestampPatterns(List<String> timestampPatterns) {
        this.timestampPatterns = timestampPatterns;
    }

    public String getZone() {
        return zone;
    }

    public void setZone(String zone) {
        this.zone = zone;
    }

    public Upload getUpload() {
        return upload;
    }

    /** Upload limits. */
    public static class Upload {

        private long maxBytes = UploadLimits.DEFAULT_MAX_BYTES;

        private int maxRows = UploadLimits.DEFAULT_MAX_ROWS;

        public long getMaxBytes() {
            return maxBytes;
        }

        public void setMaxBytes(long maxBytes) {
            this.maxBytes = maxBytes;
        }

        public int getMaxRows() {
            return maxRows;
        }

        public void setMaxRows(int maxRows) {
            this.maxRows = maxRows;
        }
    }
}
