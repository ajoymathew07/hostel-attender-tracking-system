package duty.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * What Flyway built matches the data model: tables, named constraints, indexes and version columns,
 * and there is no stored points column anywhere.
 */
@SpringBootTest
class SchemaMigrationIntegrationTest extends AbstractIntegrationTest {

    /**
     * Expected tables: the data model's, minus {@code category_term} (dropped with the classifier) plus
     * {@code app_setting}.
     */
    private static final List<String> EXPECTED_TABLES =
            List.of(
                    "app_user",
                    "audit_event",
                    "app_setting",
                    "availability_window",
                    "complaint",
                    "floor",
                    "floor_assignment",
                    "ingestion_run",
                    "point_ledger",
                    "push_subscription",
                    "source_row",
                    "task",
                    "task_assignment");

    /** The named constraints the design calls out by name. */
    private static final List<String> EXPECTED_CONSTRAINTS =
            List.of(
                    "uq_user_email",
                    "uq_floor",
                    "uq_fa",
                    "uq_push_endpoint",
                    "uq_source_row",
                    "uq_complaint_ref",
                    "uq_task_attender",
                    "uq_ledger_idem",
                    "uq_setting_name",
                    "ck_user_role",
                    "ck_coverage",
                    "ck_avail_status",
                    "ck_sr_state",
                    "ck_domain",
                    "ck_priority",
                    "ck_task_type",
                    "ck_task_link",
                    // Not in the data model doc: added with `task.slots` for shared duties.
                    "ck_task_slots",
                    "ck_asg_method",
                    "ck_ledger_reason");

    /** The indexes section 3 declares. */
    private static final List<String> EXPECTED_INDEXES =
            List.of(
                    "ix_fa_lookup",
                    "ix_avail_lookup",
                    "ix_push_user",
                    "ix_sr_hash",
                    "ix_complaint_status",
                    "ix_complaint_domain",
                    "ix_complaint_sla",
                    "ix_docket_no",
                    "ix_ledger_period",
                    "ix_ledger_att",
                    "ix_audit_entity",
                    "ix_audit_time");

    /** Section 7.4: every mutable aggregate root carries a version column. */
    private static final List<String> VERSIONED_TABLES =
            List.of("complaint", "task", "task_assignment", "floor_assignment", "app_user");

    @Autowired private DataSource dataSource;
    @Autowired private Flyway flyway;

    @Test
    @DisplayName("Flyway migrated cleanly from empty, with nothing pending or failed")
    void migrationsApplyCleanly() {
        MigrationInfo[] applied = flyway.info().applied();

        assertThat(applied).describedAs("the schema is owned by Flyway, not by Hibernate").isNotEmpty();
        assertThat(applied)
                .allSatisfy(
                        info -> {
                            assertThat(info.getState()).isEqualTo(MigrationState.SUCCESS);
                            assertThat(info.getState().isFailed()).isFalse();
                        });
        assertThat(flyway.info().pending()).isEmpty();
    }

    @Test
    @DisplayName("every table the application maps exists, and nothing maps a table that does not")
    void everyExpectedTableExists() throws SQLException {
        List<String> actual = queryStrings("select table_name from information_schema.tables where table_schema = 'public'");

        assertThat(actual).containsAll(EXPECTED_TABLES);
    }

    @Test
    @DisplayName("every named unique and check constraint of section 3 is present")
    void namedConstraintsArePresent() throws SQLException {
        List<String> actual = queryStrings("select conname from pg_constraint");

        assertThat(actual).containsAll(EXPECTED_CONSTRAINTS);
    }

    @Test
    @DisplayName("every index of section 3 is present")
    void declaredIndexesArePresent() throws SQLException {
        List<String> actual = queryStrings("select indexname from pg_indexes where schemaname = 'public'");

        assertThat(actual).containsAll(EXPECTED_INDEXES);
    }

    @Test
    @DisplayName("the five aggregates of section 7.4 carry a version column, and nothing else does")
    void versionColumnsAreExactlyWhereTheDesignPutsThem() throws SQLException {
        List<String> actual =
                queryStrings(
                        "select table_name from information_schema.columns"
                                + " where table_schema = 'public' and column_name = 'version'"
                                // Flyway's own history table has a `version` column of its own.
                                + " and table_name <> '"
                                + flyway.getConfiguration().getTable()
                                + "'");

        assertThat(actual).containsExactlyInAnyOrderElementsOf(VERSIONED_TABLES);
    }

    @Test
    @DisplayName("there is no stored points balance anywhere — a balance is a SUM, not a counter")
    void noBalanceColumnExistsAnywhere() throws SQLException {
        List<String> suspicious =
                queryStrings(
                        """
                        select table_name || '.' || column_name
                          from information_schema.columns
                         where table_schema = 'public'
                           and (column_name like '%balance%'
                                or column_name in ('points', 'total_points', 'point_total', 'score'))
                           and table_name <> 'point_ledger'
                        """);

        assertThat(suspicious)
                .describedAs(
                        "the only `points` column in the schema is the signed movement on"
                                + " point_ledger; a counter anywhere else would reintroduce exactly"
                                + " the lost update the ledger exists to make impossible")
                .isEmpty();
    }

    @Test
    @DisplayName("audit_event references its subject by (entity_type, entity_id), with no foreign key")
    void auditEventHasNoForeignKeyToItsSubject() throws SQLException {
        List<String> foreignKeys =
                queryStrings(
                        """
                        select a.attname
                          from pg_constraint c
                          join pg_attribute a on a.attrelid = c.conrelid and a.attnum = any(c.conkey)
                         where c.conrelid = 'audit_event'::regclass and c.contype = 'f'
                        """);

        assertThat(foreignKeys)
                .describedAs("one log records changes to many tables, so entity_id can reference none")
                .doesNotContain("entity_id")
                .containsExactly("actor_id");
    }

    @Test
    @DisplayName("timestamps are stored without a timezone and written as UTC (NFR-7)")
    void timestampsAreStoredWithoutATimezone() throws SQLException {
        List<String> withTimezone =
                queryStrings(
                        """
                        select table_name || '.' || column_name
                          from information_schema.columns
                         where table_schema = 'public'
                           and data_type = 'timestamp with time zone'
                        """);

        assertThat(withTimezone)
                .describedAs(
                        "the JDBC layer is pinned to UTC, so a timezone-carrying column would only"
                                + " add a second, disagreeing notion of when something happened")
                .isEmpty();
    }

    private List<String> queryStrings(String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                values.add(rows.getString(1));
            }
        }
        return values;
    }
}
