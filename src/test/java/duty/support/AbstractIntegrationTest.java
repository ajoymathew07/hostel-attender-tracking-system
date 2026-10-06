package duty.support;

import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for tests that need a database.
 *
 * <p>Uses real PostgreSQL, not H2, because constraint, locking and {@code FOR UPDATE} behaviour is
 * what we need to test. One container is shared by all test classes. Tests use the {@code local}
 * profile with the container's URL.
 */
@ActiveProfiles("local")
public abstract class AbstractIntegrationTest {

    /** Same image as docker-compose.yml. */
    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:16-alpine");

    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(POSTGRES_IMAGE)
                    .withDatabaseName("duty_tracker")
                    .withUsername("duty_tracker")
                    .withPassword("duty_tracker");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }
}
