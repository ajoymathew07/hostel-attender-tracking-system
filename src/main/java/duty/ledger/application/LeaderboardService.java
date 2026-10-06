package duty.ledger.application;

import duty.identity.application.UserProvisioningService;
import duty.identity.application.UserSummary;
import duty.ledger.domain.PeriodKey;
import duty.ledger.domain.PeriodTotal;
import duty.ledger.infrastructure.PointLedgerRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The leaderboard: a derived projection, never stored state (section 7.8, C5).
 *
 * <p>There is no cached total, no materialized view and no counter column. At NFR-1 scale — a few
 * hundred rows in a period — the standings are one grouped index scan, and adding a counter would
 * trade away the very property that makes the ledger correct.
 *
 * <p><strong>The design's single SQL statement is split in two here.</strong> Section 7.8 writes it
 * as a {@code LEFT JOIN} from {@code app_user} to {@code point_ledger}, but {@code app_user}
 * belongs to the identity module and no module may read another's tables (architecture section 2.4,
 * rule 3). So the ledger sums its own rows, identity supplies the attenders, and the left join
 * becomes a map lookup over the handful of attenders a hostel has. The property the join existed
 * for is kept exactly: an attender with no activity still appears, on zero, because a missing row
 * would read as a bug to the manager.
 *
 * <p>Manager-only, on the service (section 11.3). An attender sees their own total through
 * {@link PointsLedger#myPeriodTotal()} and nobody else's.
 */
@Service
@Transactional(readOnly = true)
@PreAuthorize("hasRole('MANAGER')")
public class LeaderboardService {

    /** Section 7.8's ordering, minus the tie-break the screen has no column to show. */
    private static final Comparator<LeaderboardRow> HIGHEST_FIRST =
            Comparator.comparingLong(LeaderboardRow::total)
                    .reversed()
                    .thenComparing(LeaderboardRow::displayName);

    private final PointLedgerRepository entries;
    private final UserProvisioningService users;

    LeaderboardService(PointLedgerRepository entries, UserProvisioningService users) {
        this.entries = entries;
        this.users = users;
    }

    /** The standings for the current Asia/Kolkata month (section 7.9). */
    public Leaderboard currentStandings() {
        return standings(PeriodKey.ofInstant(Instant.now()));
    }

    /** The standings for one {@code 'YYYY-MM'} period. */
    public Leaderboard standings(String periodKey) {
        Map<Long, Long> totals = new HashMap<>();
        for (PeriodTotal total : entries.totalsForPeriod(periodKey)) {
            totals.put(total.attenderId(), total.total());
        }
        List<LeaderboardRow> rows =
                users.listAssignableAttenders().stream()
                        .map(attender -> toRow(attender, totals))
                        .sorted(HIGHEST_FIRST)
                        .toList();
        return new Leaderboard(periodKey, rows);
    }

    private static LeaderboardRow toRow(UserSummary attender, Map<Long, Long> totals) {
        return new LeaderboardRow(
                attender.id(), attender.displayName(), totals.getOrDefault(attender.id(), 0L));
    }
}
