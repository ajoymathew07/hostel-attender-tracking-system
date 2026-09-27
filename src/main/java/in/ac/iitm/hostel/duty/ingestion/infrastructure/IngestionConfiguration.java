package in.ac.iitm.hostel.duty.ingestion.infrastructure;

import in.ac.iitm.hostel.duty.ingestion.domain.ColumnMapping;
import in.ac.iitm.hostel.duty.ingestion.domain.RowNormalizer;
import in.ac.iitm.hostel.duty.ingestion.domain.UploadLimits;
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
 * Turns {@link IngestionProperties} into the domain value objects the pipeline uses, and switches
 * on the locked scheduler the Sheets poll runs under.
 *
 * <p>The three value objects are domain types, so they cannot carry {@code @Component} — the
 * domain layer depends on nothing outside itself and the JDK (architecture section 2.4, rule 1).
 * Declaring them as beans here is how a framework-free object still reaches a Spring constructor,
 * and it keeps the "which YAML key means what" knowledge in one readable place.
 *
 * <p><strong>Scheduling is enabled here</strong> rather than in a configuration of its own,
 * because the Sheets poll is the only scheduled job in the system today. When the analytics and
 * SLA jobs of section 2.3 arrive, the two {@code @Enable} annotations and the lock provider move
 * up to a shared configuration; until then a second file would only be a place to look.
 *
 * <p>The lock lives in the one database (section 2.6) and is read with the <em>database's</em>
 * clock, not the instance's: the whole point is to serialise two instances, and two instances are
 * exactly the situation in which two clocks disagree.
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
