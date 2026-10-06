package duty.ledger.domain;

import java.util.Objects;

/**
 * The idempotency keys of data model section 3.6, in one place.
 *
 * <p>This is the centre of the correctness argument in section 7.3. The key for an automatic credit
 * is derived <strong>deterministically from facts that identify the work</strong> — the task and
 * the attender — and never from anything about the request. A retried mobile request on patchy
 * hostel Wi-Fi therefore collides on {@code uq_ledger_idem} and is swallowed as a no-op, whether
 * the duplicate arrives 20 milliseconds or 20 minutes later.
 *
 * <p>This is also why test X2 is the sharpest test in the suite: even a buggy client generating a
 * fresh UUID per retry cannot double-credit, because the ledger key ignores the client's key
 * entirely.
 */
public final class IdempotencyKeys {

    private IdempotencyKeys() {}

    /**
     * {@code RESOLVE:{task}:{attender}}, with a {@code :r{n}} suffix after a reopen.
     *
     * <p>The suffix is what lets the reopen cycle work (transition T17): a legitimate re-resolution
     * produces a <em>different</em> key and is credited again, so the constraint blocks duplicates
     * without ever blocking genuine repeat work.
     *
     * @param reopenCount how many times the complaint has been reopened; 0 on the first resolution
     */
    public static String forResolve(Long taskId, Long attenderId, int reopenCount) {
        if (reopenCount < 0) {
            throw new IllegalArgumentException("reopenCount must not be negative");
        }
        String base = "RESOLVE:" + require(taskId, "taskId") + ":" + require(attenderId, "attenderId");
        return reopenCount > 0 ? base + ":r" + reopenCount : base;
    }

    /**
     * {@code DUTY:{task}:{attender}}.
     *
     * <p>Each assignee of a shared duty has their own key, so simultaneous completions credit
     * different keys and never contend (section 7.5).
     */
    public static String forSpecialDuty(Long taskId, Long attenderId) {
        return "DUTY:" + require(taskId, "taskId") + ":" + require(attenderId, "attenderId");
    }

    /** {@code PENALTY:{uuid}} — the key comes from the manager's request. */
    public static String forPenalty(String requestId) {
        return "PENALTY:" + requireText(requestId, "requestId");
    }

    /** {@code ADJ:{uuid}} — the key comes from the manager's request. */
    public static String forAdjustment(String requestId) {
        return "ADJ:" + requireText(requestId, "requestId");
    }

    /** {@code REV:{reverses_id}} — one reversal per target row, enforced by the constraint. */
    public static String forReversal(Long reversesId) {
        return "REV:" + require(reversesId, "reversesId");
    }

    private static Long require(Long value, String field) {
        return Objects.requireNonNull(value, field);
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
