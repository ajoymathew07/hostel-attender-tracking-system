package duty.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import duty.ledger.domain.PointLedgerEntry.ReasonCode;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What a ledger entry refuses to build. */
class PointLedgerEntryTest {

    private static final Instant AT = Instant.parse("2026-02-01T12:00:00Z");

    @Test
    @DisplayName("period_key is always derived from effective_date, never supplied")
    void periodKeyIsDerived() {
        PointLedgerEntry credit =
                PointLedgerEntry.credit(
                        1L,
                        7L,
                        ReasonCode.COMPLAINT_RESOLVED,
                        2,
                        LocalDate.of(2026, 10, 1),
                        "RESOLVE:7:1",
                        99L,
                        AT);

        assertThat(credit.getPeriodKey()).isEqualTo("2026-10");
        assertThat(credit.getEffectiveDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(credit.getAttenderId()).isEqualTo(1L);
        assertThat(credit.getTaskId()).isEqualTo(7L);
        assertThat(credit.getPoints()).isEqualTo(2);
        assertThat(credit.getReasonCode()).isEqualTo(ReasonCode.COMPLAINT_RESOLVED);
        assertThat(credit.getIdempotencyKey()).isEqualTo("RESOLVE:7:1");
        assertThat(credit.getCreatedBy()).isEqualTo(99L);
        assertThat(credit.getCreatedAt()).isEqualTo(AT);
        assertThat(credit.getNote()).isNull();
        assertThat(credit.getReversesId()).isNull();
        assertThat(credit.getId()).isNull();
    }

    @Test
    @DisplayName("a manual adjustment without a note is refused — the manager must say why")
    void manualAdjustmentDemandsANote() {
        assertThatThrownBy(
                        () ->
                                PointLedgerEntry.managerEntry(
                                        1L,
                                        ReasonCode.MANUAL_ADJUSTMENT,
                                        5,
                                        LocalDate.of(2026, 2, 1),
                                        "  ",
                                        "ADJ:abc",
                                        99L,
                                        AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MANUAL_ADJUSTMENT");

        PointLedgerEntry justified =
                PointLedgerEntry.managerEntry(
                        1L,
                        ReasonCode.MANUAL_ADJUSTMENT,
                        5,
                        LocalDate.of(2026, 2, 1),
                        "agreed at the monthly review",
                        "ADJ:abc",
                        99L,
                        AT);
        assertThat(justified.getNote()).isEqualTo("agreed at the monthly review");
        assertThat(justified.getTaskId()).isNull();
    }

    @Test
    @DisplayName("a penalty needs no note and carries no task")
    void penaltyNeedsNoNote() {
        PointLedgerEntry penalty =
                PointLedgerEntry.managerEntry(
                        1L,
                        ReasonCode.PENALTY,
                        -1,
                        LocalDate.of(2026, 2, 1),
                        null,
                        "PENALTY:abc",
                        99L,
                        AT);

        assertThat(penalty.getPoints()).isEqualTo(-1);
        assertThat(penalty.getNote()).isNull();
        assertThat(penalty.getPeriodKey()).isEqualTo("2026-02");
    }

    @Test
    @DisplayName("an unpersisted entry cannot be reversed — REV:{id} needs an id")
    void unpersistedEntryCannotBeReversed() {
        PointLedgerEntry notYetSaved =
                PointLedgerEntry.credit(
                        1L,
                        7L,
                        ReasonCode.COMPLAINT_RESOLVED,
                        2,
                        LocalDate.of(2026, 2, 1),
                        "RESOLVE:7:1",
                        99L,
                        AT);

        assertThatThrownBy(() -> notYetSaved.reversal(99L, AT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("persisted");
    }

    @Test
    @DisplayName("required fields are rejected as null rather than reaching the NOT NULL column")
    void requiredFieldsAreChecked() {
        assertThatThrownBy(
                        () ->
                                PointLedgerEntry.credit(
                                        null,
                                        7L,
                                        ReasonCode.COMPLAINT_RESOLVED,
                                        2,
                                        LocalDate.of(2026, 2, 1),
                                        "k",
                                        99L,
                                        AT))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(
                        () ->
                                PointLedgerEntry.credit(
                                        1L, 7L, ReasonCode.COMPLAINT_RESOLVED, 2, null, "k", 99L, AT))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(
                        () ->
                                PointLedgerEntry.credit(
                                        1L,
                                        7L,
                                        ReasonCode.COMPLAINT_RESOLVED,
                                        2,
                                        LocalDate.of(2026, 2, 1),
                                        null,
                                        99L,
                                        AT))
                .isInstanceOf(NullPointerException.class);
    }
}
