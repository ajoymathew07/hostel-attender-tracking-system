package duty.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The key shapes of data model section 3.6 — a plain unit test, no Spring, no database.
 *
 * <p>These strings are the whole of the double-crediting argument (section 7.3), so they are worth
 * pinning exactly: if the key for a given piece of work ever stopped being deterministic, the
 * unique constraint would still hold and yet stop preventing anything.
 */
class IdempotencyKeysTest {

    @Test
    @DisplayName("the resolve key is derived from the work alone, so a retry produces the same key")
    void resolveKeyIsDeterministic() {
        assertThat(IdempotencyKeys.forResolve(42L, 7L, 0)).isEqualTo("RESOLVE:42:7");
        assertThat(IdempotencyKeys.forResolve(42L, 7L, 0))
                .describedAs("nothing about the request contributes to it")
                .isEqualTo(IdempotencyKeys.forResolve(42L, 7L, 0));
    }

    @Test
    @DisplayName("different work produces different keys — one attender's credit never blocks another's")
    void differentWorkProducesDifferentKeys() {
        assertThat(IdempotencyKeys.forResolve(42L, 7L, 0))
                .isNotEqualTo(IdempotencyKeys.forResolve(42L, 8L, 0))
                .isNotEqualTo(IdempotencyKeys.forResolve(43L, 7L, 0));
    }

    @Test
    @DisplayName("the reopen suffix lets genuine repeat work be credited again — T17")
    void reopenSuffixDistinguishesARecredit() {
        assertThat(IdempotencyKeys.forResolve(42L, 7L, 1)).isEqualTo("RESOLVE:42:7:r1");
        assertThat(IdempotencyKeys.forResolve(42L, 7L, 3)).isEqualTo("RESOLVE:42:7:r3");
        assertThat(IdempotencyKeys.forResolve(42L, 7L, 1))
                .isNotEqualTo(IdempotencyKeys.forResolve(42L, 7L, 0));
    }

    @Test
    @DisplayName("each assignee of a shared duty gets their own key, so they never contend — 7.5")
    void sharedDutyKeysArePerAttender() {
        assertThat(IdempotencyKeys.forSpecialDuty(9L, 1L)).isEqualTo("DUTY:9:1");
        assertThat(IdempotencyKeys.forSpecialDuty(9L, 1L))
                .isNotEqualTo(IdempotencyKeys.forSpecialDuty(9L, 2L));
    }

    @Test
    @DisplayName("manager-raised entries carry the key from their request")
    void managerKeysComeFromTheRequest() {
        assertThat(IdempotencyKeys.forPenalty("abc-123")).isEqualTo("PENALTY:abc-123");
        assertThat(IdempotencyKeys.forAdjustment("abc-123")).isEqualTo("ADJ:abc-123");
        assertThat(IdempotencyKeys.forReversal(88L)).isEqualTo("REV:88");
    }

    @Test
    @DisplayName("a malformed key is rejected at construction, not discovered at the constraint")
    void malformedInputIsRejected() {
        assertThatThrownBy(() -> IdempotencyKeys.forResolve(42L, 7L, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdempotencyKeys.forResolve(null, 7L, 0))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> IdempotencyKeys.forSpecialDuty(9L, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> IdempotencyKeys.forPenalty("  "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdempotencyKeys.forAdjustment(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> IdempotencyKeys.forReversal(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("only MANUAL_ADJUSTMENT demands a note")
    void noteIsMandatoryOnlyForAdjustments() {
        assertThat(ReasonCode.MANUAL_ADJUSTMENT.requiresNote()).isTrue();
        assertThat(ReasonCode.PENALTY.requiresNote()).isFalse();
        assertThat(ReasonCode.COMPLAINT_RESOLVED.requiresNote()).isFalse();
        assertThat(ReasonCode.SPECIAL_DUTY.requiresNote()).isFalse();
        assertThat(ReasonCode.REVERSAL.requiresNote()).isFalse();
    }
}
