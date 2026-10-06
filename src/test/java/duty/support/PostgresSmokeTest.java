package duty.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** The test container is up and Flyway ran with nothing pending or failed. */
@SpringBootTest
class PostgresSmokeTest extends AbstractIntegrationTest {

    @Autowired private DataSource dataSource;

    @Autowired private Flyway flyway;

    @Test
    @DisplayName("the datasource is reachable and points at the PostgreSQL 16 container")
    void datasourceIsReachable() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("select 1")) {

            assertThat(connection.isValid(5)).isTrue();
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getInt(1)).isEqualTo(1);
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
            assertThat(connection.getMetaData().getDatabaseMajorVersion()).isEqualTo(16);
        }
    }

    @Test
    @DisplayName("Flyway ran against db/migration with nothing pending or failed")
    void flywayRan() throws SQLException {
        assertThat(flyway.getConfiguration().getLocations())
                .extracting(Object::toString)
                .anyMatch(location -> location.contains("db/migration"));

        MigrationInfo[] all = flyway.info().all();
        assertThat(all)
                .describedAs("no migration may be left pending or failed after startup")
                .allSatisfy(
                        info -> {
                            assertThat(info.getState().isFailed()).isFalse();
                            assertThat(info.getState()).isNotEqualTo(MigrationState.PENDING);
                        });

        assertThat(Arrays.stream(all).filter(info -> info.getState() == MigrationState.PENDING))
                .describedAs("startup must leave no pending migration")
                .isEmpty();

        assertThat(flywaySchemaHistoryExists())
                .describedAs("Flyway must have taken ownership of the schema")
                .isTrue();
    }

    private boolean flywaySchemaHistoryExists() throws SQLException {
        String table = flyway.getConfiguration().getTable();
        try (Connection connection = dataSource.getConnection();
                ResultSet tables =
                        connection.getMetaData().getTables(null, null, table, new String[] {"TABLE"})) {
            return tables.next();
        }
    }
}
