package in.ac.iitm.hostel.duty.workflow.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Section 4.5's P1 rule: the safety words, matched exactly and never fuzzily. */
class SafetyKeywordsTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Getting a shock from the geyser switch",
                "A spark from the switchboard in 214",
                "No water in the bathroom since morning",
                "Sewage backing up in the corridor",
                "Short circuit in the corridor wiring",
                "SHOCK FROM THE GEYSER" // shouted, and still a hazard
            })
    @DisplayName("a safety keyword makes the complaint P1")
    void aSafetyKeywordIsP1(String text) {
        assertThat(SafetyKeywords.basePriorityFor(text)).isEqualTo(Priority.P1);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Gettng a shok from the geyser", // misspelt
                "Sprk from the switchboard", // misspelt
                "Sparkling water tap is broken", // 'spark' only as a substring
                "No hot water in the bathroom", // not the phrase 'no water'
                "shortcircuit", // run together, so not the phrase
                "Water pressure is low in the mornings"
            })
    @DisplayName("a near miss is NOT P1 — a false P1 distorts the whole queue")
    void aNearMissIsNotP1(String text) {
        assertThat(SafetyKeywords.basePriorityFor(text))
                .describedAs("matching is exact, so a misspelling is P2 and gets its ordinary window")
                .isEqualTo(Priority.P2);
    }

    @Test
    @DisplayName("a confidently classified ordinary complaint defaults to P2")
    void theDefaultIsP2() {
        assertThat(SafetyKeywords.basePriorityFor("Tube light flickering in the common room"))
                .isEqualTo(Priority.P2);
        assertThat(SafetyKeywords.basePriorityFor(null)).isEqualTo(Priority.P2);
        assertThat(SafetyKeywords.basePriorityFor("   ")).isEqualTo(Priority.P2);
    }
}
