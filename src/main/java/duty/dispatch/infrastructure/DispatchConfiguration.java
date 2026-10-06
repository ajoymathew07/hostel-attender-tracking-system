package duty.dispatch.infrastructure;

import duty.dispatch.domain.DispatchPolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Creates the {@link DispatchPolicy} bean from YAML. Domain classes cannot carry Spring annotations,
 * so the bean is declared here.
 */
@Configuration
@EnableConfigurationProperties(DispatchProperties.class)
class DispatchConfiguration {

    @Bean
    DispatchPolicy dispatchPolicy(DispatchProperties properties) {
        return properties.toPolicy();
    }
}
