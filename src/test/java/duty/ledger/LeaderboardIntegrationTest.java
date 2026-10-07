package duty.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import duty.identity.domain.AppUser;
import duty.identity.infrastructure.AppUserRepository;
import duty.ledger.application.LeaderboardService.Leaderboard;
import duty.ledger.application.LeaderboardService.LeaderboardRow;
import duty.ledger.application.LeaderboardService;
import duty.ledger.application.PointsLedger;
import duty.ledger.domain.PointLedgerEntry.IdempotencyKeys;
import duty.ledger.domain.PointLedgerEntry.PeriodKey;
import duty.ledger.domain.PointLedgerEntry.ReasonCode;
import duty.ledger.domain.PointLedgerEntry;
import duty.ledger.infrastructure.PointLedgerRepository;
import duty.support.AbstractIntegrationTest;
import duty.support.Fixtures;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The leaderboard is computed from the ledger. Assertions only look at this test's attenders, since the
 * database is shared.
 */
@SpringBootTest
class LeaderboardIntegrationTest extends AbstractIntegrationTest {

    @Autowired private LeaderboardService leaderboard;
    @Autowired private PointsLedger ledger;
    @Autowired private PointLedgerRepository entries;
    @Autowired private AppUserRepository users;

    private AppUser manager;
    private AppUser kumar;
    private AppUser ravi;
    private AppUser idle;

    @BeforeEach
    void provision() {
        manager = users.saveAndFlush(Fixtures.manager());
        kumar = users.saveAndFlush(Fixtures.attender());
        ravi = users.saveAndFlush(Fixtures.attender());
        idle = users.saveAndFlush(Fixtures.attender());

        // The standings are manager-only, so reading them at all needs a manager in the context.
        Fixtures.signIn(manager);
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private static long total(Leaderboard board, AppUser attender) {
        return board.rows().stream()
                .filter(row -> row.attenderId() == attender.getId())
                .mapToLong(LeaderboardRow::total)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row for " + attender.getId()));
    }

    /** Adds a ledger row that needs no task, so these tests cover only the leaderboard. */
    private void credit(AppUser attender, int points, Instant at) {
        entries.saveAndFlush(
                PointLedgerEntry.managerEntry(
                        attender.getId(),
                        ReasonCode.MANUAL_ADJUSTMENT,
                        points,
                        PeriodKey.businessDateOf(at),
                        "leaderboard fixture",
                        IdempotencyKeys.forAdjustment(Fixtures.unique("lb")),
                        manager.getId(),
                        at));
    }

    @Test
    @DisplayName("an attender's standing equals the sum of their ledger rows for the period")
    void theTotalIsTheSumOfTheLedger() {
        Instant now = Instant.now();
        credit(kumar, 2, now);
        credit(kumar, 3, now);
        credit(kumar, -1, now);

        String period = PeriodKey.ofInstant(now);
        assertThat(total(leaderboard.standings(period), kumar))
                .describedAs("a balance is a SUM over immutable facts, never a counter — 7.3")
                .isEqualTo(entries.balanceOf(kumar.getId(), period))
                .isEqualTo(4L);
    }

    @Test
    @DisplayName("an attender with no activity still appears, on zero — the LEFT JOIN of 7.8")
    void anIdleAttenderStillAppears() {
        assertThat(leaderboard.currentStandings().rows())
                .describedAs("a missing row would read to the manager as a bug, not a quiet month")
                .anySatisfy(
                        row -> {
                            assertThat(row.attenderId()).isEqualTo(idle.getId());
                            assertThat(row.total()).isZero();
                        });
    }

    @Test
    @DisplayName("standings are ordered highest first")
    void higherTotalsRankAbove() {
        Instant now = Instant.now();
        credit(kumar, 4, now);
        credit(ravi, 2, now);

        assertThat(leaderboard.currentStandings().rows())
                .filteredOn(row -> row.attenderId() == kumar.getId() || row.attenderId() == ravi.getId())
                .extracting(LeaderboardRow::attenderId)
                .containsExactly(kumar.getId(), ravi.getId());
    }

    @Test
    @DisplayName("a period shows only its own rows — period_key is the business month, 7.9")
    void eachPeriodIsScopedToItself() {
        Instant now = Instant.now();
        Instant twoMonthsAgo = now.minus(60, ChronoUnit.DAYS);
        credit(kumar, 2, now);
        credit(kumar, 7, twoMonthsAgo);

        assertThat(total(leaderboard.standings(PeriodKey.ofInstant(now)), kumar)).isEqualTo(2L);
        assertThat(total(leaderboard.standings(PeriodKey.ofInstant(twoMonthsAgo)), kumar))
                .describedAs("the month a row lands in is its effective date's, not today's")
                .isEqualTo(7L);
    }

    @Test
    @DisplayName("the leaderboard is the manager's screen; an attender is refused — 11.3")
    void everybodyElsesStandingIsManagerOnly() {
        Fixtures.signIn(kumar);
        assertThatThrownBy(() -> leaderboard.currentStandings())
                .isInstanceOf(AccessDeniedException.class);

        Fixtures.signIn(manager);
        assertThat(leaderboard.currentStandings().rows()).isNotEmpty();
    }

    @Test
    @DisplayName("an attender reads their own total and nobody has to pass an id — E20")
    void myStandingIsScopedToTheCaller() {
        credit(ravi, 2, Instant.now());

        Fixtures.signIn(ravi);
        assertThat(ledger.myPeriodTotal()).isEqualTo(2L);

        Fixtures.signIn(kumar);
        assertThat(ledger.myPeriodTotal()).isZero();
    }
}
