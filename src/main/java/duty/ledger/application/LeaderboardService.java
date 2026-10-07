package duty.ledger.application;

import duty.identity.application.UserProvisioningService.UserSummary;
import duty.identity.application.UserProvisioningService;
import duty.ledger.domain.PointLedgerEntry.PeriodKey;
import duty.ledger.domain.PointLedgerEntry.PeriodTotal;
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
 * The leaderboard, computed from the ledger on every request rather than stored.
 *
 * <p>The ledger sums its own rows and the identity module supplies the attenders, because no module
 * reads another's tables. Manager-only.
 */
@Service
@Transactional(readOnly = true)
@PreAuthorize("hasRole('MANAGER')")
public class LeaderboardService {

    /** Highest total first, then by name. */
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

    /** The standings for one {@code YYYY-MM} period, highest first. */
    public record Leaderboard(String periodKey, List<LeaderboardRow> rows) {}

    /** One attender's total for the period; {@code 0} if they have no points, so they still appear. */
    public record LeaderboardRow(long attenderId, String displayName, long total) {}
}
