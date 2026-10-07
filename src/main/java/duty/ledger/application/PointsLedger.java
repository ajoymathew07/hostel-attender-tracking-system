package duty.ledger.application;

import duty.identity.application.CurrentUser;
import duty.ledger.domain.PointLedgerEntry.IdempotencyKeys;
import duty.ledger.domain.PointLedgerEntry.PeriodKey;
import duty.ledger.domain.PointLedgerEntry.ReasonCode;
import duty.ledger.domain.PointLedgerEntry;
import duty.ledger.infrastructure.PointLedgerRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes points and reads the signed-in attender's own total.
 *
 * <p>A repeat credit is detected by looking up its key first, because catching the constraint
 * violation would roll back the whole resolve. The real guarantee is still the unique constraint on
 * the key, which stops two simultaneous inserts.
 */
@Service
@Transactional
public class PointsLedger {

    private final PointLedgerRepository entries;

    PointsLedger(PointLedgerRepository entries) {
        this.entries = entries;
    }

    /**
     * Credits the points for resolving a complaint, keyed {@code RESOLVE:{task}:{attender}}.
     *
     * <p>The key comes from the work, not the request, so a retried tap cannot credit twice.
     *
     * @return {@code true} if this call wrote the credit, {@code false} if it was already there
     */
    public boolean creditForResolve(
            long attenderId, long taskId, int points, int reopenCount, Instant at) {
        String key = IdempotencyKeys.forResolve(taskId, attenderId, reopenCount);
        if (entries.findByIdempotencyKey(key).isPresent()) {
            return false;
        }
        entries.saveAndFlush(
                PointLedgerEntry.credit(
                        attenderId,
                        taskId,
                        ReasonCode.COMPLAINT_RESOLVED,
                        points,
                        PeriodKey.businessDateOf(at),
                        key,
                        // The attender who did the work is also the one who recorded it. When the
                        // manager's act-as path (section 9.4) lands it hands in a different actor;
                        // the points still go to the attender, because the manager is the typist.
                        attenderId,
                        at));
        return true;
    }

    /**
     * Whether this credit is already on the ledger, so a retried tap can succeed without changing
     * anything.
     */
    @Transactional(readOnly = true)
    public boolean alreadyCreditedForResolve(long attenderId, long taskId, int reopenCount) {
        return entries
                .findByIdempotencyKey(IdempotencyKeys.forResolve(taskId, attenderId, reopenCount))
                .isPresent();
    }

    /**
     * Reverses a resolve credit when the complaint is reopened.
     *
     * <p>The credit is never deleted: a new row with negated points is added. Repeating it is a no-op.
     */
    public void reverseResolveCredit(long attenderId, long taskId, int reopenCount, Instant at) {
        entries
                .findByIdempotencyKey(IdempotencyKeys.forResolve(taskId, attenderId, reopenCount))
                .filter(
                        credit ->
                                entries
                                        .findByIdempotencyKey(IdempotencyKeys.forReversal(credit.getId()))
                                        .isEmpty())
                .ifPresent(
                        credit ->
                                entries.saveAndFlush(
                                        credit.reversal(
                                                // The manager reopening is the one raising the
                                                // reversal (data model section 3.6).
                                                CurrentUser.idOrNull() == null
                                                        ? attenderId
                                                        : CurrentUser.idOrNull(),
                                                at)));
    }

    /**
     * The signed-in attender's total for the current month. It takes no id, so nobody can ask for
     * someone else's.
     */
    @Transactional(readOnly = true)
    public long myPeriodTotal() {
        return CurrentUser.get()
                .map(user -> entries.balanceOf(user.getUserId(), PeriodKey.ofInstant(Instant.now())))
                .orElse(0L);
    }
}
