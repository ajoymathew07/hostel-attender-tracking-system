package in.ac.iitm.hostel.duty.ledger.domain;

/**
 * Why a point movement happened, matching {@code ck_ledger_reason} (data model section 3.6).
 *
 * <p>Each code has a matching idempotency-key shape in {@link IdempotencyKeys}. The two automatic
 * codes derive their key from the work itself, which is what makes double-crediting structurally
 * impossible rather than a race to win (section 7.3).
 */
public enum ReasonCode {

    /** +2 by default, on transition to RESOLVED. Key: {@code RESOLVE:{task}:{attender}}. */
    COMPLAINT_RESOLVED,

    /** {@code task.point_value}, on duty completion. Key: {@code DUTY:{task}:{attender}}. */
    SPECIAL_DUTY,

    /** −1 by default, raised by the manager. Key: {@code PENALTY:{uuid}} from the request. */
    PENALTY,

    /** Any amount, raised by the manager with a mandatory note. Key: {@code ADJ:{uuid}}. */
    MANUAL_ADJUSTMENT,

    /** The negation of a target row, never a delete. Key: {@code REV:{reverses_id}}. */
    REVERSAL;

    /** Whether the manager must supply a note for this code. */
    public boolean requiresNote() {
        return this == MANUAL_ADJUSTMENT;
    }
}
