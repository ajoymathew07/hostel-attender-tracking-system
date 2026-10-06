package duty.ledger.infrastructure;

import duty.ledger.domain.PeriodTotal;
import duty.ledger.domain.PointLedgerEntry;
import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The points ledger (data model section 3.6).
 *
 * <p><strong>This interface deliberately extends the bare {@code Repository} marker rather than
 * {@code JpaRepository}.</strong> The table is append-only — no UPDATE, no DELETE — and inheriting
 * {@code JpaRepository} would hand every caller a {@code deleteById} and a {@code deleteAll} that
 * must never be used. Declaring only the operations the design permits makes the append-only rule
 * unbreakable through this interface rather than merely documented. A reversal is a new row with
 * negated points (transition T17).
 *
 * <p>{@code saveAndFlush} is exposed rather than {@code save} on purpose: the duplicate credit must
 * surface as a {@code DataIntegrityViolationException} at the call site, where it is swallowed as a
 * no-op, not at some later flush in a place that cannot interpret it (section 7.3).
 */
public interface PointLedgerRepository extends Repository<PointLedgerEntry, Long> {

    PointLedgerEntry saveAndFlush(PointLedgerEntry entry);

    Optional<PointLedgerEntry> findById(Long id);

    Optional<PointLedgerEntry> findByIdempotencyKey(String idempotencyKey);

    List<PointLedgerEntry> findByAttenderIdOrderByCreatedAtDesc(Long attenderId);

    List<PointLedgerEntry> findByPeriodKeyAndAttenderId(String periodKey, Long attenderId);

    long count();

    /**
     * A balance is a SUM over immutable facts, never a stored counter (section 7.3). Returns 0 for
     * an attender with no activity, so they still appear rather than vanishing (test plan E26).
     */
    @Query(
            """
            select coalesce(sum(l.points), 0) from PointLedgerEntry l
             where l.attenderId = :attenderId
               and (:periodKey is null or l.periodKey = :periodKey)
            """)
    long balanceOf(@Param("attenderId") Long attenderId, @Param("periodKey") String periodKey);

    /**
     * Every attender's total for one period, in one grouped scan over {@code ix_ledger_period} —
     * the {@code SUM} half of the leaderboard (section 7.8).
     *
     * <p>Attenders with no rows in the period are simply absent; the caller supplies them on zero,
     * which is what section 7.8's {@code LEFT JOIN} was for.
     */
    @Query(
            """
            select new duty.ledger.domain.PeriodTotal(l.attenderId, sum(l.points))
              from PointLedgerEntry l
             where l.periodKey = :periodKey
             group by l.attenderId
            """)
    List<PeriodTotal> totalsForPeriod(@Param("periodKey") String periodKey);
}
