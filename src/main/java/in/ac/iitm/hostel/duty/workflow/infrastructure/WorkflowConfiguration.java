package in.ac.iitm.hostel.duty.workflow.infrastructure;

import in.ac.iitm.hostel.duty.workflow.domain.SlaTargets;
import in.ac.iitm.hostel.duty.workflow.domain.WorkingCalendar;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Turns {@link SlaProperties} into the two domain value objects the SLA clock reads.
 *
 * <p>Both are domain types and so cannot carry {@code @Component} — the domain layer depends on
 * nothing outside itself and the JDK (architecture section 2.4, rule 1). Declaring them here is how
 * a framework-free object still reaches a Spring constructor, and it keeps "which YAML key means
 * what" in one readable place, exactly as {@code IngestionConfiguration} does for ingestion.
 */
@Configuration
@EnableConfigurationProperties(SlaProperties.class)
class WorkflowConfiguration {

    @Bean
    SlaTargets slaTargets(SlaProperties properties) {
        return properties.toSlaTargets();
    }

    @Bean
    WorkingCalendar workingCalendar(SlaProperties properties) {
        return properties.toWorkingCalendar();
    }
}
