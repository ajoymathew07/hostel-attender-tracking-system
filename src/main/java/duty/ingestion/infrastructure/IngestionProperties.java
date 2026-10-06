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
 * Ingestion's tunables (section 5.2), as configuration rather than constants (section 2.7).
 *
 * <p>The column mapping is the one that will actually be edited. Form question wording and column
 * order will change (OQ-4), and when they do the fix must be a YAML edit, not a redeploy.
 *
 * <p><strong>One deviation from the design's YAML.</strong> Section 5.2 writes this block at
 * {@code ingestion.sheets.columns}. It is at {@code ingestion.columns} here, because the CSV
 * fallback is a download of the very same response sheet (section 5.8) and therefore has the very
 * same headers. Nesting the mapping under {@code sheets} would mean either a second copy for CSV —
 * the exact drift the shared port exists to prevent — or a CSV adapter reading its configuration
 * out of a block named after the other source. Noted in the PR description.
 */
@ConfigurationProperties(prefix = "ingestion")
public class IngestionProperties {

    /** Accepted header aliases per logical field, keyed by {@link SourceField#configKey()}. */
    private Map<String, List<String>> columns = new LinkedHashMap<>();

    /** Fields whose absence fails the whole run (section 5.2). */
    private List<String> required = new ArrayList<>();

    private List<String> timestampPatterns = new ArrayList<>(RowNormalizer.DEFAULT_TIMESTAMP_PATTERNS);

    /** Form timestamps are wall-clock local time; storage is UTC (NFR-7). */
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

    /**
     * An unknown field name is a startup failure, not a shrug.
     *
     * <p>A typo would otherwise produce a mapping that silently lacks a column, which is precisely
     * the silent partial ingest section 5.2 refuses to allow.
     */
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

    /** Upload safety of section 11.6, defaulted to the design's numbers. */
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
