package in.ac.iitm.hostel.duty.categorization.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The first half of section 5.5's pipeline: normalize, expand, tokenize, n-gram. */
class ComplaintTextTest {

    @Test
    @DisplayName("lowercases, strips punctuation and collapses whitespace")
    void normalizes() {
        assertThat(ComplaintText.of("  Fan NOT working!!  Room-214.  ").tokens())
                .containsExactly("fan", "not", "working", "room", "214");
    }

    @Test
    @DisplayName("abbreviations expand before tokenizing, so 'no wtr' can match the phrase")
    void expandsAbbreviations() {
        assertThat(ComplaintText.of("no wtr").tokens()).containsExactly("no", "water");
        assertThat(ComplaintText.of("AC not cooling").tokens())
                .containsExactly("air", "conditioner", "not", "cooling");
    }

    @Test
    @DisplayName("phrases run to three tokens and are offered longest first")
    void phrasesAreLongestFirst() {
        ComplaintText text = ComplaintText.of("no water today");

        assertThat(text.phrases())
                .extracting(ComplaintText.Phrase::text)
                .containsExactly("no water today", "no water", "water today", "no", "water", "today");
    }

    @Test
    @DisplayName("taking a phrase's tokens is what stops its own words being counted again")
    void takenTokensSuppressTheirPhrase() {
        ComplaintText text = ComplaintText.of("no water today");
        boolean[] taken = new boolean[text.tokens().size()];
        ComplaintText.Phrase noWater =
                text.phrases().stream().filter(phrase -> phrase.text().equals("no water")).findFirst().orElseThrow();

        assertThat(noWater.overlaps(taken)).isFalse();
        noWater.take(taken);

        assertThat(noWater.overlaps(taken)).isTrue();
        assertThat(text.phrases().stream().filter(phrase -> phrase.text().equals("water")))
                .allMatch(phrase -> phrase.overlaps(taken));
        assertThat(text.phrases().stream().filter(phrase -> phrase.text().equals("today")))
                .noneMatch(phrase -> phrase.overlaps(taken));
    }

    @Test
    @DisplayName("neither the text nor a phrase prints the resident's words — section 11.4")
    void nothingPrintsTheResidentsWords() {
        ComplaintText text = ComplaintText.of("sewage smell outside room 214");

        assertThat(text).hasToString("ComplaintText[5 tokens]");
        assertThat(text.phrases().get(0)).hasToString("Phrase[start=0, size=3]");
    }

    @Test
    @DisplayName("only single tokens are offered for fuzzy matching")
    void onlySingleTokensAreFuzzyCandidates() {
        ComplaintText text = ComplaintText.of("tube light");

        assertThat(text.phrases()).filteredOn(ComplaintText.Phrase::isSingleToken).hasSize(2);
    }
}
