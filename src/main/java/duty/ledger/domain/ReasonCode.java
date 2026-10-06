package duty.ledger.domain;

/** Why a point movement happened. Matches the {@code ck_ledger_reason} constraint. */
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
