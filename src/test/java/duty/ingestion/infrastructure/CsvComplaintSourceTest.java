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
import duty.ingestion.domain.UploadLimits;
import duty.ingestion.domain.UploadRejectedException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The CSV source produces raw rows only: columns by header name, limits enforced, no dedupe or
 * complaint logic.
 */
class CsvComplaintSourceTest {

    private static final ColumnMapping MAPPING =
            new ColumnMapping(
                    Map.of(
                            SourceField.TIMESTAMP, List.of("Timestamp"),
                            SourceField.EMAIL, List.of("Email Address"),
                            SourceField.NAME, List.of("Name"),
                            SourceField.ROLL_NO, List.of("Roll Number"),
                            SourceField.PHONE, List.of("Phone Number"),
                            SourceField.ROOM, List.of("Room Number"),
                            SourceField.DESCRIPTION, List.of("Describe the issue"),
                            SourceField.CATEGORY, List.of("Type of issue")),
                    Set.of(SourceField.TIMESTAMP, SourceField.DESCRIPTION));

    private static ComplaintSource source(String csv) {
        return new CsvComplaintSource(csv.getBytes(StandardCharsets.UTF_8), MAPPING, UploadLimits.defaults());
    }

    @Test
    @DisplayName("the adapter announces itself as the CSV channel with a source ref that never varies")
    void theSourceRefIsStableAcrossUploads() {
        ComplaintSource first = source("Timestamp,Describe the issue\n2026/02/01 09:00:00,No water\n");
        ComplaintSource second = source("Timestamp,Describe the issue\n2026/03/09 11:00:00,Fan broken\n");

        assertThat(first.type()).isEqualTo(SourceType.CSV_UPLOAD);
        assertThat(first.sourceRef()).isEqualTo(second.sourceRef()).isNotBlank();
    }

    @Test
    @DisplayName("rows come back mapped by header name, whatever order the columns are in")
    void rowsAreMappedByHeaderName() {
        List<RawRow> rows =
                source(
                                """
                                Describe the issue,Room Number,Timestamp,Email Address
                                No water since morning,214,2026/02/01 09:00:00,synthetic@example.com
                                """)
                        .fetch(FetchWindow.all());

        assertThat(rows).hasSize(1);
        RawRow row = rows.get(0);
        assertThat(row.get(SourceField.DESCRIPTION)).isEqualTo("No water since morning");
        assertThat(row.get(SourceField.ROOM)).isEqualTo("214");
        assertThat(row.get(SourceField.TIMESTAMP)).isEqualTo("2026/02/01 09:00:00");
        assertThat(row.get(SourceField.EMAIL)).isEqualTo("synthetic@example.com");
    }

    @Test
    @DisplayName("the row index is the file's line number, so a diagnostic points at the right line")
    void rowIndexIsTheLineNumber() {
        List<RawRow> rows =
                source(
                                """
                                Timestamp,Describe the issue
                                2026/02/01 09:00:00,First
                                2026/02/01 09:01:00,Second
                                """)
                        .fetch(FetchWindow.all());

        assertThat(rows).extracting(RawRow::rowIndex).containsExactly(2, 3);
    }

    @Test
    @DisplayName("a Form that does not ask for name, roll number or phone still ingests")
    void optionalColumnsMayBeAbsentEntirely() {
        List<RawRow> rows =
                source("Timestamp,Describe the issue\n2026/02/01 09:00:00,No water\n").fetch(FetchWindow.all());

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get(SourceField.NAME)).isNull();
        assertThat(rows.get(0).get(SourceField.ROLL_NO)).isNull();
        assertThat(rows.get(0).get(SourceField.PHONE)).isNull();
    }

    @Test
    @DisplayName("a missing required column fails before a single row is produced")
    void aMissingRequiredColumnFailsTheFetch() {
        assertThatThrownBy(
                        () ->
                                source("Timestamp,Room Number\n2026/02/01 09:00:00,214\n")
                                        .fetch(FetchWindow.all()))
                .isInstanceOf(MissingRequiredColumnException.class)
                .hasMessageContaining("description");
    }

    @Test
    @DisplayName("quoted cells keep their commas, newlines and doubled quotes")
    void quotingFollowsRfc4180() {
        List<RawRow> rows =
                source(
                                "Timestamp,Describe the issue\r\n"
                                        + "2026/02/01 09:00:00,\"Water, everywhere\"\r\n"
                                        + "2026/02/01 09:01:00,\"He said \"\"it is fixed\"\", it is not\"\r\n"
                                        + "2026/02/01 09:02:00,\"line one\nline two\"\r\n")
                        .fetch(FetchWindow.all());

        assertThat(rows).extracting(row -> row.get(SourceField.DESCRIPTION))
                .containsExactly(
                        "Water, everywhere",
                        "He said \"it is fixed\", it is not",
                        "line one\nline two");
    }

    @Test
    @DisplayName("a UTF-8 byte order mark does not become part of the first header")
    void theByteOrderMarkIsStripped() {
        byte[] withBom =
                ("﻿Timestamp,Describe the issue\n2026/02/01 09:00:00,No water\n")
                        .getBytes(StandardCharsets.UTF_8);

        List<RawRow> rows =
                new CsvComplaintSource(withBom, MAPPING, UploadLimits.defaults()).fetch(FetchWindow.all());

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get(SourceField.TIMESTAMP)).isEqualTo("2026/02/01 09:00:00");
    }

    @Test
    @DisplayName("a short row is read, not rejected — trailing blank answers are simply omitted")
    void shortRowsAreTolerated() {
        List<RawRow> rows =
                source("Timestamp,Describe the issue,Room Number\n2026/02/01 09:00:00,No water\n")
                        .fetch(FetchWindow.all());

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get(SourceField.ROOM)).isNull();
    }

    @Test
    @DisplayName("blank lines are skipped rather than becoming empty complaints")
    void blankLinesAreSkipped() {
        List<RawRow> rows =
                source("Timestamp,Describe the issue\n\n2026/02/01 09:00:00,No water\n\n")
                        .fetch(FetchWindow.all());

        assertThat(rows).hasSize(1);
    }

    @Test
    @DisplayName("a file over the row limit is refused, and no row is produced from it")
    void theRowLimitIsEnforcedWhileReading() {
        StringBuilder csv = new StringBuilder("Timestamp,Describe the issue\n");
        for (int i = 0; i < 12; i++) {
            csv.append("2026/02/01 09:00:00,No water\n");
        }
        ComplaintSource tight =
                new CsvComplaintSource(
                        csv.toString().getBytes(StandardCharsets.UTF_8), MAPPING, new UploadLimits(1024, 10));

        assertThatThrownBy(() -> tight.fetch(FetchWindow.all()))
                .isInstanceOf(UploadRejectedException.class)
                .hasMessageContaining("10");
    }

    @Test
    @DisplayName("a file with no header row at all is refused rather than silently ingesting nothing")
    void anEmptyFileIsRefused() {
        assertThatThrownBy(() -> source("   \n").fetch(FetchWindow.all()))
                .isInstanceOf(UploadRejectedException.class);
    }

    @Test
    @DisplayName("a header row and nothing else is a valid, empty fetch")
    void aHeaderOnlyFileYieldsNoRows() {
        assertThat(source("Timestamp,Describe the issue\n").fetch(FetchWindow.all())).isEmpty();
    }

    @Test
    @DisplayName("the fetch window skips rows the caller already holds")
    void theFetchWindowSkipsEarlierRows() {
        String csv =
                """
                Timestamp,Describe the issue
                2026/02/01 09:00:00,First
                2026/02/01 09:01:00,Second
                2026/02/01 09:02:00,Third
                """;

        assertThat(source(csv).fetch(new FetchWindow(3)))
                .extracting(row -> row.get(SourceField.DESCRIPTION))
                .containsExactly("Second", "Third");
    }

    @Test
    @DisplayName("every cell of the row is carried, so the content hash sees the whole response")
    void allCellsAreCarried() {
        List<RawRow> rows =
                source("Timestamp,Describe the issue,Extra\n2026/02/01 09:00:00,No water,kept\n")
                        .fetch(FetchWindow.all());

        assertThat(rows.get(0).cells()).containsExactly("2026/02/01 09:00:00", "No water", "kept");
        assertThat(rows.get(0).headers()).containsExactly("Timestamp", "Describe the issue", "Extra");
    }
}
