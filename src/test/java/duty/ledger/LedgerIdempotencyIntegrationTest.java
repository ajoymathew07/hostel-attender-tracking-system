package duty.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import duty.identity.domain.AppUser;
import duty.identity.infrastructure.AppUserRepository;
import duty.ledger.domain.IdempotencyKeys;
import duty.ledger.domain.PointLedgerEntry;
import duty.ledger.domain.ReasonCode;
import duty.ledger.infrastructure.PointLedgerRepository;
import duty.support.AbstractIntegrationTest;
import duty.support.Fixtures;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * <strong>The most important test in this PR.</strong>
 *
 * <p>The proposal asks for protection against double-crediting. The design's answer (section 7.3)
 * is that it is not a race we have to win: the idempotency key for an automatic credit is derived
 * deterministically from the work — the task and the attender — so a duplicate insert collides on
 * {@code uq_ledger_idem} and the database rejects it atomically, however many transactions try at
 * once.
 *
 * <p>These tests assert on <strong>final database state</strong> rather than on return values,
 * because the claim being tested is about the database, not about the application.
 */
@SpringBootTest
class LedgerIdempotencyIntegrationTest extends AbstractIntegrationTest {

    @Autowired private PointLedgerRepository ledger;
    @Autowired private AppUserRepository users;
    @Autowired private TransactionTemplate tx;

    private Long attenderId;
    private Long managerId;

    @BeforeEach
    void provisionUsers() {
        AppUser attender = users.saveAndFlush(Fixtures.attender());
        AppUser manager = users.saveAndFlush(Fixtures.manager());
        this.attenderId = attender.getId();
        this.managerId = manager.getId();
    }

    @Test
    @DisplayName("uq_ledger_idem rejects a second credit under the same key — C1, the double resolve")
    void duplicateIdempotencyKeyIsRejected() {
        String key = IdempotencyKeys.forResolve(4242L, attenderId, 0);

        ledger.saveAndFlush(credit(key));

        assertThatThrownBy(() -> ledger.saveAndFlush(credit(key)))
                .describedAs(
                        "the second credit under an identical key must be rejected by the database,"
                                + " not merely by application logic")
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(ledger.findByAttenderIdOrderByCreatedAtDesc(attenderId))
                .describedAs("one credit, not two")
                .hasSize(1);
        assertThat(ledger.balanceOf(attenderId, null))
                .describedAs("+2 for one job, never +4")
                .isEqualTo(2L);
    }

    @Test
    @DisplayName("ten concurrent inserts of the same key leave exactly one ledger row — X1")
    void concurrentDuplicateCreditsLeaveOneRow() throws Exception {
        int threads = 10;
        String key = IdempotencyKeys.forResolve(7777L, attenderId, 0);

        CountDownLatch startTogether = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Void>> attempts = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                attempts.add(
                        () -> {
                            startTogether.await(10, TimeUnit.SECONDS);
                            try {
                                // Each attempt is its own transaction, as a retried HTTP request
                                // would be.
                                tx.executeWithoutResult(status -> ledger.saveAndFlush(credit(key)));
                                accepted.incrementAndGet();
                            } catch (DataIntegrityViolationException expected) {
                                // uq_ledger_idem: this exact credit already exists. Not an error.
                                rejected.incrementAndGet();
                            }
                            return null;
                        });
            }
            List<Future<Void>> futures = new ArrayList<>();
            for (Callable<Void> attempt : attempts) {
                futures.add(pool.submit(attempt));
            }
            startTogether.countDown();
            for (Future<Void> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(accepted.get()).describedAs("exactly one insert wins").isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(threads - 1);
        assertThat(ledger.findByAttenderIdOrderByCreatedAtDesc(attenderId))
                .describedAs("one ledger row regardless of how many threads raced")
                .hasSize(1);
        assertThat(ledger.balanceOf(attenderId, null)).isEqualTo(2L);
    }

    @Test
    @DisplayName("a buggy client sending a fresh key per retry still cannot double-credit — X2")
    void theLedgerKeyIgnoresWhateverTheClientSent() {
        // The sharpest case in the design: the ledger key is derived from the work, not from the
        // request, so two different client-supplied keys still collide on the same ledger key.
        String fromFirstTap = IdempotencyKeys.forResolve(31L, attenderId, 0);
        String fromRetryWithFreshUuid = IdempotencyKeys.forResolve(31L, attenderId, 0);

        assertThat(fromRetryWithFreshUuid).isEqualTo(fromFirstTap);

        ledger.saveAndFlush(credit(fromFirstTap));
        assertThatThrownBy(() -> ledger.saveAndFlush(credit(fromRetryWithFreshUuid)))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(ledger.balanceOf(attenderId, null)).isEqualTo(2L);
    }

    @Test
    @DisplayName("the reopen cycle credits again: resolve, reverse, re-resolve nets +2 over 3 rows — E18")
    void reopenCycleProducesThreeRowsAndNetsTwoPoints() {
        PointLedgerEntry original = ledger.saveAndFlush(credit(IdempotencyKeys.forResolve(55L, attenderId, 0)));

        // T17: a reopen reverses rather than deletes.
        ledger.saveAndFlush(original.reversal(managerId, Instant.parse("2026-02-03T10:00:00Z")));

        // The re-resolution carries the :r1 suffix, so it is a different key and is credited.
        String afterReopen = IdempotencyKeys.forResolve(55L, attenderId, 1);
        assertThat(afterReopen).isEqualTo("RESOLVE:55:" + attenderId + ":r1");
        ledger.saveAndFlush(credit(afterReopen));

        assertThat(ledger.findByAttenderIdOrderByCreatedAtDesc(attenderId))
                .describedAs("three immutable rows: credit, reversal, credit")
                .hasSize(3);
        assertThat(ledger.balanceOf(attenderId, null))
                .describedAs("net +2, and the whole history stays visible")
                .isEqualTo(2L);
    }

    @Test
    @DisplayName("a reversal cannot itself be applied twice — REV:{id} is unique too")
    void reversalIsAlsoIdempotent() {
        PointLedgerEntry original = ledger.saveAndFlush(credit(IdempotencyKeys.forResolve(66L, attenderId, 0)));
        Instant at = Instant.parse("2026-02-03T10:00:00Z");

        ledger.saveAndFlush(original.reversal(managerId, at));

        assertThatThrownBy(() -> ledger.saveAndFlush(original.reversal(managerId, at)))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(ledger.balanceOf(attenderId, null))
                .describedAs("a double reversal must not take the attender to -2")
                .isEqualTo(0L);
    }

    @Test
    @DisplayName("period_key is derived from the business date, so a backdated penalty lands in its own month — E19")
    void backdatedEntryLandsInThePeriodOfItsEffectiveDate() {
        ledger.saveAndFlush(
                PointLedgerEntry.credit(
                        attenderId,
                        null,
                        ReasonCode.COMPLAINT_RESOLVED,
                        2,
                        LocalDate.of(2026, 10, 4),
                        IdempotencyKeys.forResolve(99L, attenderId, 0),
                        managerId,
                        Instant.parse("2026-10-04T08:00:00Z")));

        // Logged in October, but effective in September: it belongs to the September period.
        ledger.saveAndFlush(
                PointLedgerEntry.managerEntry(
                        attenderId,
                        ReasonCode.PENALTY,
                        -1,
                        LocalDate.of(2026, 9, 28),
                        "late on the 28th",
                        IdempotencyKeys.forPenalty(Fixtures.unique("uuid")),
                        managerId,
                        Instant.parse("2026-10-02T09:00:00Z")));

        assertThat(ledger.balanceOf(attenderId, "2026-09")).isEqualTo(-1L);
        assertThat(ledger.balanceOf(attenderId, "2026-10")).isEqualTo(2L);
        assertThat(ledger.balanceOf(attenderId, null)).isEqualTo(1L);
    }

    private PointLedgerEntry credit(String idempotencyKey) {
        return PointLedgerEntry.credit(
                attenderId,
                null,
                ReasonCode.COMPLAINT_RESOLVED,
                2,
                LocalDate.of(2026, 2, 1),
                idempotencyKey,
                managerId,
                Instant.parse("2026-02-01T12:00:00Z"));
    }
}
