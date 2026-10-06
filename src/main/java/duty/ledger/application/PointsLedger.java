package duty.ledger.application;

import duty.identity.application.CurrentUser;
import duty.ledger.domain.IdempotencyKeys;
import duty.ledger.domain.PeriodKey;
import duty.ledger.domain.PointLedgerEntry;
import duty.ledger.domain.ReasonCode;
import duty.ledger.infrastructure.PointLedgerRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writing points, and reading your own balance — the ledger module's published surface
 * (architecture section 2.4, rule 3).
 *
 * <p><strong>Why the duplicate is detected by a lookup and not by catching the violation.</strong>
 * Section 7.3 sketches the credit as a {@code saveAndFlush} wrapped in a
 * {@code catch (DataIntegrityViolationException)} that logs and carries on. Caught inside the
 * transaction that caused it, that cannot be a no-op: a constraint violation marks the current
 * transaction rollback-only, so the resolve it is nested in would fail anyway and the "swallow"
 * would swallow nothing. The replay is therefore recognised by looking the key up first, which is
 * one indexed read on a unique column.
 *
 * <p>That lookup is <em>not</em> the guarantee — a check-then-insert is itself a race. The
 * guarantee is still {@code uq_ledger_idem}: two genuinely simultaneous resolves both find nothing,
 * both insert, and the database admits exactly one of them however the application is written. The
 * lookup only turns the common case — the same attender tapping twice on a flaky connection
 * (section 7.4) — into a clean success instead of a rolled-back request.
 */
@Service
@Transactional
public class PointsLedger {

    private final PointLedgerRepository entries;

    PointsLedger(PointLedgerRepository entries) {
        this.entries = entries;
    }

    /**
     * The credit for resolving a complaint (transition T14): {@code RESOLVE:{task}:{attender}}.
     *
     * <p>The key is derived from the work — the task and the attender — and from nothing about the
     * request, which is what makes double-crediting structurally impossible rather than a race to
     * win (section 7.3).
     *
     * @param reopenCount how many times the complaint has been reopened; the {@code :r{n}} suffix
     *     is what lets a legitimate re-resolution after T17 be credited again under a new key
     * @param at the instant of resolution; the effective date is its Asia/Kolkata business date,
     *     so a job finished at 02:00 IST on the 1st belongs to the new month (section 7.9)
     * @return {@code true} if this call wrote the credit, {@code false} if it was already applied
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
     * Whether this exact credit is already on the ledger — the replay of section 7.4.
     *
     * <p>The caller needs the answer <em>before</em> it starts moving state, because a retried tap
     * must be a clean success that touches nothing, not a transition attempted from a state the
     * table no longer allows.
     */
    @Transactional(readOnly = true)
    public boolean alreadyCreditedForResolve(long attenderId, long taskId, int reopenCount) {
        return entries
                .findByIdempotencyKey(IdempotencyKeys.forResolve(taskId, attenderId, reopenCount))
                .isPresent();
    }

    /**
     * Transition T17 — the compensating row for a reopen.
     *
     * <p>Append-only means the credit is never deleted: the reversal is a new row with negated
     * points, keyed {@code REV:{reverses_id}}, and both stay visible in the attender's history.
     * The second lookup makes a repeated reopen a no-op rather than a constraint violation, for
     * the same reason the credit's does (section 7.3).
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
     * The signed-in attender's total for the current period — their standing (section 10.2).
     *
     * <p>Scoped to the caller rather than taking an id, for the same reason the queue is: an
     * attender id in the signature is an attender id someone can change in a form post (E20).
     * Anyone else's standing is the manager's {@link LeaderboardService}.
     */
    @Transactional(readOnly = true)
    public long myPeriodTotal() {
        return CurrentUser.get()
                .map(user -> entries.balanceOf(user.getUserId(), PeriodKey.ofInstant(Instant.now())))
                .orElse(0L);
    }
}
