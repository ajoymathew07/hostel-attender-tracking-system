package in.ac.iitm.hostel.duty.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for every test that needs a database.
 *
 * <p>All database tests run against real PostgreSQL, never an in-memory substitute: the properties
 * we most need to verify — unique-constraint behaviour under concurrent inserts, optimistic-lock
 * failures, {@code SELECT ... FOR UPDATE} — are exactly the ones H2 would fake convincingly and
 * wrongly (test plan section 12.1, ADR-001).
 *
 * <p><strong>Singleton container.</strong> The container is started once in a static initializer and
 * deliberately never stopped; Testcontainers' Ryuk sidecar reaps it when the JVM exits. Every
 * subclass therefore shares one PostgreSQL instance instead of paying a container start per test
 * class.
 */
public abstract class AbstractIntegrationTest {

    /** Matches the image in docker-compose.yml, so local and test behaviour cannot diverge. */
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
