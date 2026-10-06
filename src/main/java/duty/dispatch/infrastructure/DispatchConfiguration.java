package duty.dispatch.infrastructure;

import duty.dispatch.domain.DispatchPolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Turns {@link DispatchProperties} into the policy the dispatcher scores with.
 *
 * <p>{@link DispatchPolicy} is a domain type and so cannot carry {@code @Component} (architecture
 * section 2.4, rule 1); declaring it here is how a framework-free object reaches a Spring
 * constructor, exactly as {@code WorkflowConfiguration} does for the SLA clock.
 */
@Configuration
@EnableConfigurationProperties(DispatchProperties.class)
class DispatchConfiguration {

    @Bean
    DispatchPolicy dispatchPolicy(DispatchProperties properties) {
        return properties.toPolicy();
    }
}
