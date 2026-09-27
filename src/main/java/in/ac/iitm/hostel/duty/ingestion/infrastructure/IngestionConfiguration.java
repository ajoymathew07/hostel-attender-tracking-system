package in.ac.iitm.hostel.duty.ingestion.infrastructure;

import in.ac.iitm.hostel.duty.ingestion.domain.ColumnMapping;
import in.ac.iitm.hostel.duty.ingestion.domain.RowNormalizer;
import in.ac.iitm.hostel.duty.ingestion.domain.UploadLimits;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Turns {@link IngestionProperties} into the domain value objects the pipeline uses.
 *
 * <p>These three are domain types, so they cannot carry {@code @Component} — the domain layer
 * depends on nothing outside itself and the JDK (architecture section 2.4, rule 1). Declaring them
 * as beans here is how a framework-free object still reaches a Spring constructor, and it keeps
 * the "which YAML key means what" knowledge in one readable place.
 */
@Configuration
@EnableConfigurationProperties(IngestionProperties.class)
class IngestionConfiguration {

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
}
