package duty.ingestion.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import duty.ingestion.domain.ColumnMapping;
import duty.ingestion.domain.ComplaintSource;
import duty.ingestion.domain.FetchWindow;
import duty.ingestion.domain.MissingRequiredColumnException;
import duty.ingestion.domain.RawRow;
import duty.ingestion.domain.SourceField;
import duty.ingestion.domain.SourceType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Sheets source, with {@link SheetReader} stubbed with realistic API quirks (values as
 * {@code Object}, missing trailing cells, blank rows). It produces raw rows only.
 *
 * <p>All student details here are made up.
 */
class GoogleSheetsSourceTest {

    private static final String SHEET_ID = "1AbCdEfGhIjKlMnOpQrStUvWxYz";
    private static final String SHEET_NAME = "Form Responses 1";

    private static final ColumnMapping MAPPING =
            new ColumnMapping(
                    Map.of(
                            SourceField.TIMESTAMP, List.of("Timestamp"),
                            SourceField.EMAIL, List.of("Email Address"),
                            SourceField.NAME, List.of("Name"),
                            SourceField.ROOM, List.of("Room Number"),
                            SourceField.DESCRIPTION, List.of("Describe the issue")),
                    Set.of(SourceField.TIMESTAMP, SourceField.DESCRIPTION));

    /** Records the range requested, so the A1 notation can be checked. */
    private final List<String> rangesRequested = new ArrayList<>();

    private ComplaintSource source(List<List<Object>> grid) {
        SheetReader reader =
                (spreadsheetId, range) -> {
                    assertThat(spreadsheetId).isEqualTo(SHEET_ID);
                    rangesRequested.add(range);
                    return grid;
                };
        return new GoogleSheetsSource(reader, SHEET_ID, SHEET_NAME, MAPPING);
    }

    private static List<Object> row(Object... cells) {
        return List.of(cells);
    }

    @Test
    @DisplayName("the sheet id is the source ref, which is what makes uq_source_row bite")
    void theSheetIdIsTheSourceRef() {
        ComplaintSource source = source(List.of(row("Timestamp", "Describe the issue")));

        assertThat(source.type()).isEqualTo(SourceType.GOOGLE_SHEETS);
        assertThat(source.sourceRef()).isEqualTo(SHEET_ID);
    }

    @Test
    @DisplayName("rows come back mapped by header name, whatever order the columns are in")
    void rowsAreMappedByHeaderName() {
        List<RawRow> rows =
                source(
                                List.of(
                                        row("Describe the issue", "Room Number", "Timestamp", "Email Address"),
                                        row(
                                                "No water since morning",
                                                "214",
                                                "2026/02/01 09:00:00",
                                                "synthetic@example.com")))
                        .fetch(FetchWindow.all());

        assertThat(rows).hasSize(1);
        RawRow only = rows.get(0);
        assertThat(only.get(SourceField.DESCRIPTION)).isEqualTo("No water since morning");
        assertThat(only.get(SourceField.ROOM)).isEqualTo("214");
        assertThat(only.get(SourceField.TIMESTAMP)).isEqualTo("2026/02/01 09:00:00");
        assertThat(only.get(SourceField.EMAIL)).isEqualTo("synthetic@example.com");

        // One request, for the whole tab, in quoted A1 notation — the sheet name has spaces.
        assertThat(rangesRequested).containsExactly("'Form Responses 1'!A:ZZ");
    }

    @Test
    @DisplayName("row indexes are the sheet's own row numbers, the header being row 1")
    void rowIndexesAreSheetRowNumbers() {
        List<RawRow> rows =
                source(
                                List.of(
                                        row("Timestamp", "Describe the issue"),
                                        row("2026/02/01 09:00:00", "First"),
                                        row("2026/02/01 09:05:00", "Second")))
                        .fetch(FetchWindow.all());

        assertThat(rows).extracting(RawRow::rowIndex).containsExactly(2, 3);
    }

    @Test
    @DisplayName("the window skips rows below it, and the header is still read")
    void theWindowSkipsRowsBelowIt() {
        List<List<Object>> grid =
                List.of(
                        row("Timestamp", "Describe the issue"),
                        row("2026/02/01 09:00:00", "First"),
                        row("2026/02/01 09:05:00", "Second"),
                        row("2026/02/01 09:10:00", "Third"));

        List<RawRow> rows = source(grid).fetch(new FetchWindow(4));

        assertThat(rows)
                .extracting(RawRow::rowIndex)
                .containsExactly(4);
        assertThat(rows.get(0).get(SourceField.DESCRIPTION)).isEqualTo("Third");
    }

    @Test
    @DisplayName("section 5.3's overlap: the window reaches 50 rows back behind the watermark")
    void theWindowOverlapsFiftyRows() {
        assertThat(GoogleSheetsSource.windowFrom(null)).isEqualTo(FetchWindow.all());
        assertThat(GoogleSheetsSource.windowFrom(200).fromRowIndex()).isEqualTo(150);
        // Never below row 1, however early the watermark sits.
        assertThat(GoogleSheetsSource.windowFrom(7).fromRowIndex()).isEqualTo(1);
    }

    @Test
    @DisplayName("short rows and blank rows are what a real spreadsheet looks like, and are handled")
    void shortAndBlankRowsAreHandled() {
        List<RawRow> rows =
                source(
                                List.of(
                                        row("Timestamp", "Describe the issue", "Room Number"),
                                        // The API omits trailing empty cells entirely.
                                        row("2026/02/01 09:00:00", "No room given"),
                                        row("", "", ""),
                                        row("2026/02/01 09:20:00", "Bulb fused", "808")))
                        .fetch(FetchWindow.all());

        assertThat(rows).extracting(RawRow::rowIndex).containsExactly(2, 4);
        assertThat(rows.get(0).get(SourceField.ROOM)).isNull();
        assertThat(rows.get(1).get(SourceField.ROOM)).isEqualTo("808");
    }

    @Test
    @DisplayName("a renamed required column fails the fetch before a single row is produced")
    void aMissingRequiredColumnFailsTheFetch() {
        List<List<Object>> grid =
                List.of(
                        row("Timestamp", "What is wrong"),
                        row("2026/02/01 09:00:00", "No water"));

        assertThatThrownBy(() -> source(grid).fetch(FetchWindow.all()))
                .isInstanceOf(MissingRequiredColumnException.class)
                .hasMessageContaining("description")
                .hasMessageContaining("Describe the issue");
    }

    @Test
    @DisplayName("an empty tab has no header row, so it fails loudly rather than ingesting nothing quietly")
    void anEmptyTabFailsLoudly() {
        assertThatThrownBy(() -> source(List.of()).fetch(FetchWindow.all()))
                .isInstanceOf(MissingRequiredColumnException.class);
    }

    @Test
    @DisplayName("nothing a resident wrote reaches toString, which is what a log statement reaches for")
    void toStringCarriesNoCells() {
        assertThat(source(List.of()).toString()).contains(SHEET_ID).doesNotContain("Email");
    }
}
