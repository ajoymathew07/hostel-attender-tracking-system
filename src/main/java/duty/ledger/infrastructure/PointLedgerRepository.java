package duty.ledger.infrastructure;

import duty.ledger.domain.PointLedgerEntry.PeriodTotal;
import duty.ledger.domain.PointLedgerEntry;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** The points ledger. Extends the bare {@code Repository} so no update or delete method exists. */
public interface PointLedgerRepository extends Repository<PointLedgerEntry, Long> {

    PointLedgerEntry saveAndFlush(PointLedgerEntry entry);

    Optional<PointLedgerEntry> findById(Long id);

    Optional<PointLedgerEntry> findByIdempotencyKey(String idempotencyKey);

    List<PointLedgerEntry> findByAttenderIdOrderByCreatedAtDesc(Long attenderId);

    List<PointLedgerEntry> findByPeriodKeyAndAttenderId(String periodKey, Long attenderId);

    List<PointLedgerEntry> findByPeriodKeyOrderByCreatedAtDesc(String periodKey);

    long count();

    /** One attender's total for a period; 0 if they have none. */
    @Query(
            """
            select coalesce(sum(l.points), 0) from PointLedgerEntry l
             where l.attenderId = :attenderId
               and (:periodKey is null or l.periodKey = :periodKey)
            """)
    long balanceOf(@Param("attenderId") Long attenderId, @Param("periodKey") String periodKey);

    /** Every attender's total for one period. Attenders with no rows are absent. */
    @Query(
            """
            select new duty.ledger.domain.PointLedgerEntry$PeriodTotal(l.attenderId, sum(l.points))
              from PointLedgerEntry l
             where l.periodKey = :periodKey
             group by l.attenderId
            """)
    List<PeriodTotal> totalsForPeriod(@Param("periodKey") String periodKey);
}
