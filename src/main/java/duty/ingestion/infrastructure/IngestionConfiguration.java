package duty.ingestion.infrastructure;

import duty.ingestion.domain.ColumnMapping.SourceField;
import duty.ingestion.domain.ColumnMapping;
import duty.ingestion.domain.RowNormalizer;
import duty.ingestion.domain.UploadLimits;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Creates ingestion's domain objects from YAML, and turns on scheduling with a database lock.
 *
 * <p>Domain classes cannot carry Spring annotations, so their beans are declared here. Scheduling is
 * enabled here for the whole app; the SLA ageing job uses it too. The lock uses the database clock,
 * because two instances may disagree on the time.
 */
@Configuration
@EnableConfigurationProperties({IngestionConfiguration.IngestionProperties.class, IngestionConfiguration.SheetsProperties.class})
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "10m")
public class IngestionConfiguration {

    @Bean
    ColumnMapping ingestionColumnMapping(IngestionProperties properties) {
        return properties.toColumnMapping();
    }

    @Bean
    UploadLimits ingestionUploadLimits(IngestionProperties properties) {
        return properties.toUploadLimits();
    }

    @Bean
    RowNormalizer ingestionRowNormalizer(IngestionProperties properties) {
        return properties.toRowNormalizer();
    }

    @Bean
    LockProvider ingestionLockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .usingDbTime()
                        .build());
    }

    /**
     * Ingestion settings from {@code application.yml}: column mapping, required fields, timezone, upload
     * limits.
     *
     * <p>The mapping is under {@code ingestion.columns}, not {@code ingestion.sheets}, because the CSV is a
     * download of the same sheet and has the same headers.
     */
    @ConfigurationProperties(prefix = "ingestion")
    public static class IngestionProperties {

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

    /**
     * Which sheet to read, how often, and the credential to read it with.
     *
     * <p>All may be missing (e.g. on a developer machine); the app still starts and CSV upload still works.
     */
    @ConfigurationProperties(prefix = "ingestion.sheets")
    public static class SheetsProperties {

        /** The response sheet's id; also its {@code source_ref}. */
        private String spreadsheetId = "";

        /** The tab name; Google Forms uses this by default. */
        private String sheetName = "Form Responses 1";

        /** Base64 service-account JSON, from {@code GOOGLE_SA_KEY}. */
        private String credentials = "";

        /** How often to poll. */
        private Duration pollInterval = Duration.ofMinutes(3);

        /** Whether there is both a sheet to read and a credential to read it with. */
        public boolean configured() {
            return !spreadsheetId.isBlank() && !credentials.isBlank();
        }

        public String getSpreadsheetId() {
            return spreadsheetId;
        }

        public void setSpreadsheetId(String spreadsheetId) {
            this.spreadsheetId = spreadsheetId;
        }

        public String getSheetName() {
            return sheetName;
        }

        public void setSheetName(String sheetName) {
            this.sheetName = sheetName;
        }

        public String getCredentials() {
            return credentials;
        }

        public void setCredentials(String credentials) {
            this.credentials = credentials;
        }

        public Duration getPollInterval() {
            return pollInterval;
        }

        public void setPollInterval(Duration pollInterval) {
            this.pollInterval = pollInterval;
        }

        /** Never prints the credential; Spring and Actuator may print this object. */
        @Override
        public String toString() {
            return "SheetsProperties[spreadsheetId="
                    + spreadsheetId
                    + ", sheetName="
                    + sheetName
                    + ", credentials="
                    + (credentials.isBlank() ? "absent" : "present")
                    + "]";
        }
    }
}
