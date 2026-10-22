package duty.ledger.application;

import duty.audit.application.AuditTrail.AuditDetail;
import duty.audit.application.AuditTrail.AuditEntry;
import duty.audit.application.AuditTrail;
import duty.identity.application.CurrentUser;
import duty.ledger.domain.PointLedgerEntry.IdempotencyKeys;
import duty.ledger.domain.PointLedgerEntry.PeriodKey;
import duty.ledger.domain.PointLedgerEntry.ReasonCode;
import duty.ledger.domain.PointLedgerEntry;
import duty.ledger.infrastructure.PointLedgerRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes points and reads them back: credits for work, the manager's penalties and adjustments, and
 * the month's movements.
 *
 * <p>A repeat credit is detected by looking up its key first, because catching the constraint
 * violation would roll back the whole resolve. The real guarantee is still the unique constraint on
 * the key, which stops two simultaneous inserts.
 */
@Service
@Transactional
public class PointsLedger {

    /** A logged infraction always costs one point (section 1). */
    public static final int PENALTY_POINTS = -1;

    private static final String ENTITY_TYPE = "point_ledger";

    private final PointLedgerRepository entries;
    private final AuditTrail audit;

    PointsLedger(PointLedgerRepository entries, AuditTrail audit) {
        this.entries = entries;
        this.audit = audit;
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
                        // Whoever recorded it: the attender, or the manager acting for them. The
                        // points go to the attender either way, because the manager is the typist.
                        CurrentUser.idOrNull() == null ? attenderId : CurrentUser.idOrNull(),
                        at));
        return true;
    }

    /**
     * Credits one assignee for their part of a special duty, keyed {@code DUTY:{task}:{attender}}, so each
     * assignee of a shared duty is credited once.
     *
     * @return {@code true} if this call wrote the credit, {@code false} if it was already there
     */
    public boolean creditForDuty(long attenderId, long taskId, int points, Instant at) {
        String key = IdempotencyKeys.forSpecialDuty(taskId, attenderId);
        if (entries.findByIdempotencyKey(key).isPresent()) {
            return false;
        }
        entries.saveAndFlush(
                PointLedgerEntry.credit(
                        attenderId,
                        taskId,
                        ReasonCode.SPECIAL_DUTY,
                        points,
                        PeriodKey.businessDateOf(at),
                        key,
                        attenderId,
                        at));
        return true;
    }

    /**
     * Logs a −1 penalty against an attender.
     *
     * @param requestId a fresh id per form, so a double-submitted form records one penalty, not two
     */
    @PreAuthorize("hasRole('MANAGER')")
    public void penalise(long attenderId, String note, String requestId) {
        managerEntry(
                attenderId,
                ReasonCode.PENALTY,
                PENALTY_POINTS,
                note,
                IdempotencyKeys.forPenalty(requestId),
                "PENALTY_LOGGED");
    }

    /**
     * Adds or takes away any number of points, with a note saying why.
     *
     * @param requestId a fresh id per form, so a double-submitted form records one adjustment
     */
    @PreAuthorize("hasRole('MANAGER')")
    public void adjust(long attenderId, int points, String note, String requestId) {
        if (points == 0) {
            throw new IllegalArgumentException("an adjustment must move at least one point");
        }
        managerEntry(
                attenderId,
                ReasonCode.MANUAL_ADJUSTMENT,
                points,
                note,
                IdempotencyKeys.forAdjustment(requestId),
                "POINTS_ADJUSTED");
    }

    /** Writes a manager's entry and its audit row, unless the same form was already recorded. */
    private void managerEntry(
            long attenderId, ReasonCode reason, int points, String note, String key, String eventType) {
        if (entries.findByIdempotencyKey(key).isPresent()) {
            return;
        }
        Instant now = Instant.now();
        PointLedgerEntry saved =
                entries.saveAndFlush(
                        PointLedgerEntry.managerEntry(
                                attenderId,
                                reason,
                                points,
                                PeriodKey.businessDateOf(now),
                                note == null || note.isBlank() ? null : note.trim(),
                                key,
                                CurrentUser.idOrNull(),
                                now));
        audit.record(
                AuditEntry.change(
                        ENTITY_TYPE,
                        saved.getId(),
                        eventType,
                        AuditDetail.of("attenderId", attenderId).and("points", points).json(),
                        CurrentUser.idOrNull(),
                        CurrentUser.roleOrNull()));
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

    /** The signed-in attender's own movements this month, newest first. */
    @Transactional(readOnly = true)
    public List<LedgerLine> myLinesThisMonth() {
        return CurrentUser.get()
                .map(
                        user ->
                                entries
                                        .findByPeriodKeyAndAttenderId(
                                                PeriodKey.ofInstant(Instant.now()), user.getUserId())
                                        .stream()
                                        .sorted(Comparator.comparing(PointLedgerEntry::getCreatedAt).reversed())
                                        .map(LedgerLine::of)
                                        .toList())
                .orElse(List.of());
    }

    /** Every attender's movements this month, newest first. */
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('MANAGER')")
    public List<LedgerLine> linesThisMonth() {
        return entries.findByPeriodKeyOrderByCreatedAtDesc(PeriodKey.ofInstant(Instant.now())).stream()
                .map(LedgerLine::of)
                .toList();
    }

    /** Every movement for one task, oldest first: a complaint's history page. */
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('MANAGER')")
    public List<LedgerLine> linesForTask(long taskId) {
        return entries.findByTaskIdOrderByCreatedAtAsc(taskId).stream().map(LedgerLine::of).toList();
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

    /** One point movement, as the screens show it. */
    public record LedgerLine(
            long attenderId, Long taskId, String reason, int points, String note, Instant createdAt) {

        static LedgerLine of(PointLedgerEntry entry) {
            return new LedgerLine(
                    entry.getAttenderId(),
                    entry.getTaskId(),
                    entry.getReasonCode().name(),
                    entry.getPoints(),
                    entry.getNote(),
                    entry.getCreatedAt());
        }
    }
}
