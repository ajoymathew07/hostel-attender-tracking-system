package duty.ingestion.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Columns are found by header name; a missing required column stops the run. */
class ColumnMappingTest {

    private static final ColumnMapping MAPPING =
            new ColumnMapping(
                    Map.of(
                            SourceField.TIMESTAMP, List.of("Timestamp", "Submitted at"),
                            SourceField.EMAIL, List.of("Email Address", "Email"),
                            SourceField.NAME, List.of("Name", "Student Name"),
                            SourceField.ROLL_NO, List.of("Roll Number", "Roll No"),
                            SourceField.PHONE, List.of("Phone Number", "Mobile"),
                            SourceField.ROOM, List.of("Room Number", "Room"),
                            SourceField.DESCRIPTION, List.of("Describe the issue", "Complaint"),
                            SourceField.CATEGORY, List.of("Type of issue")),
                    Set.of(SourceField.TIMESTAMP, SourceField.DESCRIPTION));

    @Test
    @DisplayName("a column is found by its header name wherever it sits in the row")
    void headersAreMatchedByNameNotPosition() {
        ResolvedColumns columns =
                MAPPING.resolve(List.of("Describe the issue", "Room Number", "Timestamp"));

        assertThat(columns.indexOf(SourceField.DESCRIPTION)).isEqualTo(0);
        assertThat(columns.indexOf(SourceField.ROOM)).isEqualTo(1);
        assertThat(columns.indexOf(SourceField.TIMESTAMP)).isEqualTo(2);
    }

    @Test
    @DisplayName("any configured alias resolves the same field")
    void anyAliasResolves() {
        ResolvedColumns columns = MAPPING.resolve(List.of("Submitted at", "Complaint"));

        assertThat(columns.has(SourceField.TIMESTAMP)).isTrue();
        assertThat(columns.has(SourceField.DESCRIPTION)).isTrue();
    }

    @Test
    @DisplayName("header matching tolerates case, padding and doubled spaces")
    void headerMatchingIsForgivingAboutWhitespaceAndCase() {
        ResolvedColumns columns = MAPPING.resolve(List.of("  TIMESTAMP ", "describe   the issue"));

        assertThat(columns.has(SourceField.TIMESTAMP)).isTrue();
        assertThat(columns.has(SourceField.DESCRIPTION)).isTrue();
    }

    @Test
    @DisplayName("a missing required column is a loud failure that names the column and its aliases")
    void aMissingRequiredColumnFailsLoudly() {
        assertThatThrownBy(() -> MAPPING.resolve(List.of("Timestamp", "Room Number")))
                .isInstanceOf(MissingRequiredColumnException.class)
                .hasMessageContaining("description")
                .hasMessageContaining("Describe the issue")
                .asInstanceOf(
                        org.assertj.core.api.InstanceOfAssertFactories.type(MissingRequiredColumnException.class))
                .extracting(MissingRequiredColumnException::getField)
                .isEqualTo(SourceField.DESCRIPTION);
    }

    @Test
    @DisplayName("optional columns may be absent — a Form that does not ask for them still ingests")
    void optionalColumnsMayBeAbsent() {
        ResolvedColumns columns = MAPPING.resolve(List.of("Timestamp", "Describe the issue"));

        assertThat(columns.has(SourceField.NAME)).isFalse();
        assertThat(columns.has(SourceField.ROLL_NO)).isFalse();
        assertThat(columns.has(SourceField.PHONE)).isFalse();
        assertThat(columns.indexOf(SourceField.NAME)).isNull();
    }

    @Test
    @DisplayName("a cell is read by field, and an absent or short row reads as null")
    void cellsAreReadByField() {
        ResolvedColumns columns = MAPPING.resolve(List.of("Timestamp", "Describe the issue", "Room"));

        assertThat(columns.valueIn(List.of("2026/02/01 09:00:00", "No water", "214"), SourceField.ROOM))
                .isEqualTo("214");
        assertThat(columns.valueIn(List.of("2026/02/01 09:00:00", "No water"), SourceField.ROOM)).isNull();
        assertThat(columns.valueIn(List.of("a", "b", "c"), SourceField.PHONE)).isNull();
        assertThat(columns.extract(List.of("2026/02/01 09:00:00", "No water", "214")))
                .containsEntry(SourceField.DESCRIPTION, "No water");
    }

    @Test
    @DisplayName("a required field with no configured alias is a configuration error, not a run failure")
    void requiredFieldsMustBeConfigured() {
        assertThatThrownBy(
                        () ->
                                new ColumnMapping(
                                        Map.of(SourceField.TIMESTAMP, List.of("Timestamp")),
                                        Set.of(SourceField.DESCRIPTION)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("description");
    }

    @Test
    @DisplayName("the mapping reports what it requires and which aliases it accepts")
    void theMappingDescribesItself() {
        assertThat(MAPPING.required()).containsExactlyInAnyOrder(SourceField.TIMESTAMP, SourceField.DESCRIPTION);
        assertThat(MAPPING.aliasesFor(SourceField.EMAIL)).containsExactly("Email Address", "Email");
        assertThat(MAPPING.aliasesFor(SourceField.NAME)).isNotEmpty();
    }

    @Test
    @DisplayName("a field name in configuration is matched however it is spelled")
    void configurationKeysAreMatchedLeniently() {
        assertThat(SourceField.byConfigKey("rollNo")).contains(SourceField.ROLL_NO);
        assertThat(SourceField.byConfigKey("roll-no")).contains(SourceField.ROLL_NO);
        assertThat(SourceField.byConfigKey("ROLL_NO")).contains(SourceField.ROLL_NO);
        assertThat(SourceField.byConfigKey("description")).contains(SourceField.DESCRIPTION);
        assertThat(SourceField.byConfigKey("nonsense")).isEmpty();
        assertThat(SourceField.byConfigKey(null)).isEmpty();
        assertThat(SourceField.ROLL_NO.configKey()).isEqualTo("rollNo");
    }
}
