package duty.ingestion.infrastructure;

import duty.ingestion.domain.ColumnMapping;
import duty.ingestion.domain.RowNormalizer;
import duty.ingestion.domain.UploadLimits;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
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
@EnableConfigurationProperties({IngestionProperties.class, SheetsProperties.class})
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "10m")
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

    @Bean
    LockProvider ingestionLockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .usingDbTime()
                        .build());
    }
}
