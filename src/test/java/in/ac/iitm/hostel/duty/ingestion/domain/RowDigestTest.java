package in.ac.iitm.hostel.duty.ingestion.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Identity from content, never from position (section 5.3).
 *
 * <p>The two assertions that carry the design are here: the same response hashes to the same key
 * however many times it is observed, and two residents who happen to write the same sentence get
 * two different keys — identity comes from the response, not the text.
 */
class RowDigestTest {

    @Test
    @DisplayName("the same submission always produces the same response key")
    void responseKeyIsStable() {
        String first = RowDigest.responseKey("2026/02/01 09:12:00", "ravi@smail.iitm.ac.in", 0);
        String second = RowDigest.responseKey("2026/02/01 09:12:00", "ravi@smail.iitm.ac.in", 0);

        assertThat(first).isEqualTo(second).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("two residents submitting identical text are two different responses")
    void identicalTextFromDifferentResidentsIsTwoResponses() {
        String ravi = RowDigest.responseKey("2026/02/01 09:12:00", "ravi@smail.iitm.ac.in", 0);
        String kumar = RowDigest.responseKey("2026/02/01 09:12:00", "kumar@smail.iitm.ac.in", 0);

        assertThat(ravi).isNotEqualTo(kumar);
    }

    @Test
    @DisplayName("the same resident submitting at two moments is two responses")
    void theSameResidentAtTwoMomentsIsTwoResponses() {
        assertThat(RowDigest.responseKey("2026/02/01 09:12:00", "ravi@iitm.ac.in", 0))
                .isNotEqualTo(RowDigest.responseKey("2026/02/01 09:13:00", "ravi@iitm.ac.in", 0));
    }

    @Test
    @DisplayName("email case and padding do not change identity")
    void emailIsNormalisedBeforeHashing() {
        assertThat(RowDigest.responseKey(" 2026/02/01 09:12:00 ", "  Ravi@IITM.ac.in ", 0))
                .isEqualTo(RowDigest.responseKey("2026/02/01 09:12:00", "ravi@iitm.ac.in", 0));
    }

    @Test
    @DisplayName("two submissions in the same second by the same person are split by the in-batch ordinal")
    void theOrdinalDisambiguatesWithinOneBatch() {
        String base = RowDigest.responseKey("2026/02/01 09:12:00", "ravi@iitm.ac.in", 0);
        String second = RowDigest.responseKey("2026/02/01 09:12:00", "ravi@iitm.ac.in", 1);
        String third = RowDigest.responseKey("2026/02/01 09:12:00", "ravi@iitm.ac.in", 2);

        assertThat(List.of(base, second, third)).doesNotHaveDuplicates();
        assertThat(RowDigest.responseKey("2026/02/01 09:12:00", "ravi@iitm.ac.in", 1)).isEqualTo(second);
    }

    @Test
    @DisplayName("a missing email still yields a usable key rather than an exception")
    void aMissingEmailStillHashes() {
        assertThat(RowDigest.responseKey("2026/02/01 09:12:00", null, 0)).hasSize(64);
    }

    @Test
    @DisplayName("the content hash changes when any answer changes, and only then")
    void contentHashTracksEveryCell() {
        String original = RowDigest.contentHash(List.of("2026/02/01 09:12:00", "No water", "214"));

        assertThat(RowDigest.contentHash(List.of("2026/02/01 09:12:00", "No  water", " 214")))
                .isEqualTo(original);
        assertThat(RowDigest.contentHash(List.of("2026/02/01 09:12:00", "No water", "215")))
                .isNotEqualTo(original);
        assertThat(RowDigest.contentHash(List.of("2026/02/01 09:12:00", "No water")))
                .isNotEqualTo(original);
    }

    @Test
    @DisplayName("cells are joined with a separator no answer can contain, so columns cannot smear")
    void cellBoundariesCannotBeForged() {
        assertThat(RowDigest.contentHash(List.of("ab", "c"))).isNotEqualTo(RowDigest.contentHash(List.of("a", "bc")));
    }

    @Test
    @DisplayName("normalisation folds nulls, padding and doubled whitespace, and nothing else")
    void normalisationIsMinimal() {
        assertThat(RowDigest.normalize(null)).isEmpty();
        assertThat(RowDigest.normalize("  a   b \n c ")).isEqualTo("a b c");
        assertThat(RowDigest.normalize("Case")).isEqualTo("Case");
    }
}
