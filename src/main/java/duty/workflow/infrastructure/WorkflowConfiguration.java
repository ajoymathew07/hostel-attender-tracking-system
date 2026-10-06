package duty.workflow.infrastructure;

import duty.workflow.domain.SlaTargets;
import duty.workflow.domain.WorkingCalendar;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Creates the SLA domain objects as beans from YAML. Domain classes cannot carry Spring annotations,
 * so the beans are declared here.
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
