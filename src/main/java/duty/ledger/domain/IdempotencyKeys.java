package duty.ledger.domain;

import java.util.Objects;

/**
 * Builds the ledger's idempotency keys.
 *
 * <p>Automatic credits are keyed on the task and attender, never on the request, so a retried request
 * collides on the unique key and cannot credit twice.
 */
public final class IdempotencyKeys {

    private IdempotencyKeys() {}

    /**
     * {@code RESOLVE:{task}:{attender}}, plus {@code :r{n}} after a reopen so a genuine re-resolution is
     * credited again.
     */
    public static String forResolve(Long taskId, Long attenderId, int reopenCount) {
        if (reopenCount < 0) {
            throw new IllegalArgumentException("reopenCount must not be negative");
        }
        String base = "RESOLVE:" + require(taskId, "taskId") + ":" + require(attenderId, "attenderId");
        return reopenCount > 0 ? base + ":r" + reopenCount : base;
    }

    /** {@code DUTY:{task}:{attender}}: one key per assignee of a shared duty. */
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
