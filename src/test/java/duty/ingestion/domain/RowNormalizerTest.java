package duty.ingestion.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Row normalization: timestamps read as IST and stored as UTC, and student details never appear in
 * {@code toString()} or exception messages.
 */
class RowNormalizerTest {

    private static final RowNormalizer NORMALIZER = RowNormalizer.defaults();

    private static RawRow row(Map<SourceField, String> fields) {
        return new RawRow(2, List.of("Timestamp", "Describe the issue"), List.copyOf(fields.values()), fields);
    }

    private static Map<SourceField, String> minimal() {
        Map<SourceField, String> fields = new LinkedHashMap<>();
        fields.put(SourceField.TIMESTAMP, "2026/02/01 09:12:00");
        fields.put(SourceField.DESCRIPTION, "No water in the bathroom");
        return fields;
    }

    @Test
    @DisplayName("the form timestamp is read in Asia/Kolkata and stored as UTC")
    void timestampsAreReadInKolkataAndStoredUtc() {
        NormalizedRow normalized = NORMALIZER.normalize(row(minimal()));

        assertThat(normalized.reportedAt()).isEqualTo(Instant.parse("2026-02-01T03:42:00Z"));
    }

    @Test
    @DisplayName("the description is kept verbatim — the resident's wording is never rewritten")
    void descriptionIsVerbatim() {
        Map<SourceField, String> fields = minimal();
        fields.put(SourceField.DESCRIPTION, "  Fan  SPARKING!!  ");

        assertThat(NORMALIZER.normalize(row(fields)).rawText()).isEqualTo("  Fan  SPARKING!!  ");
    }

    @Test
    @DisplayName("every configured timestamp format parses to the same instant")
    void severalTimestampFormatsAreAccepted() {
        for (String written :
                List.of("2026/02/01 09:12:00", "2026-02-01 09:12:00", "01/02/2026 09:12:00")) {
            Map<SourceField, String> fields = minimal();
            fields.put(SourceField.TIMESTAMP, written);
            assertThat(NORMALIZER.normalize(row(fields)).reportedAt())
                    .isEqualTo(Instant.parse("2026-02-01T03:42:00Z"));
        }
    }

    @Test
    @DisplayName("an unreadable timestamp is a malformed row, named by row number and nothing else")
    void anUnreadableTimestampIsAMalformedRow() {
        Map<SourceField, String> fields = minimal();
        fields.put(SourceField.TIMESTAMP, "last tuesday");

        assertThatThrownBy(() -> NORMALIZER.normalize(row(fields)))
                .isInstanceOf(MalformedRowException.class)
                .hasMessageContaining("row 2")
                .hasMessageContaining("timestamp")
                .hasMessageNotContaining("last tuesday")
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(MalformedRowException.class))
                .extracting(MalformedRowException::getRowIndex)
                .isEqualTo(2);
    }

    @Test
    @DisplayName("a blank timestamp cell is a malformed row too")
    void aBlankTimestampIsAMalformedRow() {
        Map<SourceField, String> fields = minimal();
        fields.put(SourceField.TIMESTAMP, "  ");

        assertThatThrownBy(() -> NORMALIZER.normalize(row(fields)))
                .isInstanceOf(MalformedRowException.class)
                .hasMessageContaining("timestamp");
    }

    @Test
    @DisplayName("an ISO-8601 instant is accepted as a last resort")
    void anIsoInstantIsAccepted() {
        Map<SourceField, String> fields = minimal();
        fields.put(SourceField.TIMESTAMP, "2026-02-01T03:42:00Z");

        assertThat(NORMALIZER.normalize(row(fields)).reportedAt())
                .isEqualTo(Instant.parse("2026-02-01T03:42:00Z"));
    }

    @Test
    @DisplayName("the timestamp formats and the zone are configuration, not constants")
    void theNormalizerIsConfigurable() {
        RowNormalizer utc = new RowNormalizer(List.of("dd-MM-yyyy HH:mm"), java.time.ZoneOffset.UTC);
        Map<SourceField, String> fields = minimal();
        fields.put(SourceField.TIMESTAMP, "01-02-2026 09:12");

        assertThat(utc.normalize(row(fields)).reportedAt()).isEqualTo(Instant.parse("2026-02-01T09:12:00Z"));
        assertThatThrownBy(() -> new RowNormalizer(List.of(), java.time.ZoneOffset.UTC))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a phone cell with no digits at all is null, not a stray plus sign")
    void aPhoneWithNoDigitsIsNull() {
        Map<SourceField, String> fields = minimal();
        fields.put(SourceField.PHONE, "n/a");

        assertThat(NORMALIZER.normalize(row(fields)).reporterPhone()).isNull();
    }

    @Test
    @DisplayName("a blank description is a malformed row — an empty complaint helps nobody")
    void aBlankDescriptionIsAMalformedRow() {
        Map<SourceField, String> fields = minimal();
        fields.put(SourceField.DESCRIPTION, "   ");

        assertThatThrownBy(() -> NORMALIZER.normalize(row(fields)))
                .isInstanceOf(MalformedRowException.class)
                .hasMessageContaining("description");
    }

    @Test
    @DisplayName("the room number is pulled out of the room cell")
    void roomIsExtractedFromTheRoomCell() {
        Map<SourceField, String> fields = minimal();
        fields.put(SourceField.ROOM, "Room 214");

        assertThat(NORMALIZER.normalize(row(fields)).reporterRoom()).isEqualTo("214");
    }

    @Test
    @DisplayName("the digits are taken even if someone writes a stray prefix")
    void strayPrefixesAreIgnored() {
        Map<SourceField, String> fields = minimal();
        fields.put(SourceField.ROOM, "a-214");

        // Cauvery rooms are digits only; the wing is not part of the number. Anything a resident
        // types around the digits is dropped rather than stored and passed on to dispatch.
        assertThat(NORMALIZER.normalize(row(fields)).reporterRoom()).isEqualTo("214");
    }

    @Test
    @DisplayName("both room shapes are recognised: 3-digit and 4-digit")
    void bothRoomShapesAreRecognised() {
        for (String room : new String[] {"169", "214", "312", "1099", "2043", "3007"}) {
            Map<SourceField, String> fields = minimal();
            fields.put(SourceField.ROOM, room);
            assertThat(NORMALIZER.normalize(row(fields)).reporterRoom()).isEqualTo(room);
        }
    }

    @Test
    @DisplayName("a two-letter word before a number in free text is not mistaken for a wing prefix")
    void freeTextWordsAreNotWingPrefixes() {
        Map<SourceField, String> fields = minimal();
        fields.put(SourceField.DESCRIPTION, "no water in 1108, and no water in 214 either");

        assertThat(NORMALIZER.normalize(row(fields)).reporterRoom()).isEqualTo("1108");
    }

    @Test
    @DisplayName("when the room cell says nothing useful the description is searched instead")
    void theDescriptionIsTheFallbackForTheRoom() {
        Map<SourceField, String> fields = minimal();
        fields.put(SourceField.ROOM, "upstairs");
        fields.put(SourceField.DESCRIPTION, "no water in 1108 since morning");

        assertThat(NORMALIZER.normalize(row(fields)).reporterRoom()).isEqualTo("1108");
    }

    @Test
    @DisplayName("no room anywhere is null, not a guess — an unmapped complaint routes to triage")
    void anUnknownRoomIsNull() {
        Map<SourceField, String> fields = minimal();
        fields.put(SourceField.DESCRIPTION, "the corridor light is out");

        assertThat(NORMALIZER.normalize(row(fields)).reporterRoom()).isNull();
    }

    @Test
    @DisplayName("optional student details are carried through, trimmed, upper-cased and digit-normalised")
    void optionalStudentDetailsAreNormalised() {
        Map<SourceField, String> fields = minimal();
        fields.put(SourceField.NAME, "  Synthetic Student  ");
        fields.put(SourceField.ROLL_NO, " ce21b099 ");
        fields.put(SourceField.PHONE, "+91 98765 43210");
        fields.put(SourceField.EMAIL, "  Synthetic@Example.com ");

        NormalizedRow normalized = NORMALIZER.normalize(row(fields));

        assertThat(normalized.reporterName()).isEqualTo("Synthetic Student");
        assertThat(normalized.reporterRollNo()).isEqualTo("CE21B099");
        assertThat(normalized.reporterPhone()).isEqualTo("+919876543210");
        assertThat(normalized.reporterContact()).isEqualTo("synthetic@example.com");
    }

    @Test
    @DisplayName("absent optional columns normalise to null rather than empty strings")
    void absentOptionalColumnsAreNull() {
        NormalizedRow normalized = NORMALIZER.normalize(row(minimal()));

        assertThat(normalized.reporterName()).isNull();
        assertThat(normalized.reporterRollNo()).isNull();
        assertThat(normalized.reporterPhone()).isNull();
        assertThat(normalized.reporterContact()).isNull();
        assertThat(normalized.reportedDomain()).isNull();
    }

    @Test
    @DisplayName("a blank optional cell is null, not a blank string")
    void blankOptionalCellsAreNull() {
        Map<SourceField, String> fields = minimal();
        fields.put(SourceField.NAME, "   ");
        fields.put(SourceField.PHONE, "  ");

        NormalizedRow normalized = NORMALIZER.normalize(row(fields));

        assertThat(normalized.reporterName()).isNull();
        assertThat(normalized.reporterPhone()).isNull();
    }

    @Test
    @DisplayName("the resident's category hint is carried for the classifier to use later")
    void theCategoryHintIsCarried() {
        Map<SourceField, String> fields = minimal();
        fields.put(SourceField.CATEGORY, "Electrical");

        assertThat(NORMALIZER.normalize(row(fields)).reportedDomain()).isEqualTo("Electrical");
    }

    @Test
    @DisplayName("student PII never reaches a toString — section 11.4")
    void studentDetailsStayOutOfToString() {
        Map<SourceField, String> fields = minimal();
        fields.put(SourceField.NAME, "Synthetic Student");
        fields.put(SourceField.ROLL_NO, "CE21B099");
        fields.put(SourceField.PHONE, "+919876543210");
        fields.put(SourceField.EMAIL, "synthetic@example.com");

        RawRow raw = row(fields);
        NormalizedRow normalized = NORMALIZER.normalize(raw);

        assertThat(raw.toString())
                .doesNotContain("Synthetic Student", "CE21B099", "9876543210", "synthetic@example.com")
                .contains("rowIndex=2");
        assertThat(normalized.toString())
                .doesNotContain("Synthetic Student", "CE21B099", "9876543210", "synthetic@example.com");
    }

    @Test
    @DisplayName("a raw row reads its own fields and refuses an out-of-range ordinal window")
    void rawRowsAndFetchWindows() {
        RawRow raw = row(minimal());

        assertThat(raw.get(SourceField.DESCRIPTION)).isEqualTo("No water in the bathroom");
        assertThat(raw.get(SourceField.PHONE)).isNull();
        assertThat(FetchWindow.all().fromRowIndex()).isEqualTo(1);
        assertThatThrownBy(() -> new FetchWindow(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
