package duty.workflow.domain;

/**
 * Complaint priority. Matches {@code ck_priority}.
 *
 * <p>Ageing raises P3 → P2 → P1 once a complaint is open past 1.5× its SLA window, so old work does not
 * get buried.
 */
public enum Priority {
    P1,
    P2,
    P3;

    /** One step up, capped at P1. */
    public Priority escalated() {
        return switch (this) {
            case P3 -> P2;
            case P2, P1 -> P1;
        };
    }
}
