package duty.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import duty.audit.infrastructure.AuditEventRepository;
import duty.identity.domain.AppUser;
import duty.identity.infrastructure.AppUserRepository;
import duty.ledger.application.PointsLedger.LedgerLine;
import duty.ledger.application.PointsLedger;
import duty.ledger.domain.PointLedgerEntry.ReasonCode;
import duty.ledger.domain.PointLedgerEntry;
import duty.ledger.infrastructure.PointLedgerRepository;
import duty.support.AbstractIntegrationTest;
import duty.support.Fixtures;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;

/** The manager's penalties and adjustments: append-only rows, audited, and safe to double-submit. */
@SpringBootTest
class PenaltyAndAdjustmentIntegrationTest extends AbstractIntegrationTest {

    @Autowired private PointsLedger points;
    @Autowired private PointLedgerRepository ledger;
    @Autowired private AuditEventRepository audit;
    @Autowired private AppUserRepository users;

    private AppUser manager;
    private AppUser ravi;

    @BeforeEach
    void provisionUsers() {
        manager = users.saveAndFlush(Fixtures.manager());
        ravi = users.saveAndFlush(Fixtures.attender());
        Fixtures.signIn(manager);
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private PointLedgerEntry onlyEntryOfRavi() {
        List<PointLedgerEntry> entries = ledger.findByAttenderIdOrderByCreatedAtDesc(ravi.getId());
        assertThat(entries).hasSize(1);
        return entries.get(0);
    }

    @Test
    @DisplayName("a penalty is a -1 row recorded by the manager, with an audit row")
    void aPenaltyCostsOnePoint() {
        long auditBefore = audit.count();

        points.penalise(ravi.getId(), "left the RO plant running", UUID.randomUUID().toString());

        PointLedgerEntry penalty = onlyEntryOfRavi();
        assertThat(penalty.getReasonCode()).isEqualTo(ReasonCode.PENALTY);
        assertThat(penalty.getPoints()).isEqualTo(-1);
        assertThat(penalty.getCreatedBy()).isEqualTo(manager.getId());
        assertThat(ledger.balanceOf(ravi.getId(), null)).isEqualTo(-1L);
        assertThat(audit.count()).isEqualTo(auditBefore + 1);
    }

    @Test
    @DisplayName("the same form submitted twice records one penalty, not two")
    void aDoubleSubmitIsOnePenalty() {
        String requestId = UUID.randomUUID().toString();

        points.penalise(ravi.getId(), null, requestId);
        points.penalise(ravi.getId(), null, requestId);

        assertThat(ledger.balanceOf(ravi.getId(), null)).isEqualTo(-1L);
    }

    @Test
    @DisplayName("an adjustment moves any amount but needs a note, and zero is refused")
    void anAdjustmentNeedsANote() {
        points.adjust(ravi.getId(), 4, "covered the night shift", UUID.randomUUID().toString());
        assertThat(onlyEntryOfRavi().getReasonCode()).isEqualTo(ReasonCode.MANUAL_ADJUSTMENT);
        assertThat(ledger.balanceOf(ravi.getId(), null)).isEqualTo(4L);

        assertThatThrownBy(() -> points.adjust(ravi.getId(), 2, " ", UUID.randomUUID().toString()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> points.adjust(ravi.getId(), 0, "nothing", UUID.randomUUID().toString()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an attender cannot penalise or adjust, and sees only their own movements")
    void attendersCannotWritePoints() {
        points.penalise(ravi.getId(), null, UUID.randomUUID().toString());
        AppUser kumar = users.saveAndFlush(Fixtures.attender());

        Fixtures.signIn(kumar);
        assertThatThrownBy(() -> points.penalise(ravi.getId(), null, UUID.randomUUID().toString()))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> points.adjust(kumar.getId(), 5, "self", UUID.randomUUID().toString()))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(points.myLinesThisMonth()).isEmpty();

        Fixtures.signIn(ravi);
        assertThat(points.myLinesThisMonth()).extracting(LedgerLine::points).containsExactly(-1);
    }
}
