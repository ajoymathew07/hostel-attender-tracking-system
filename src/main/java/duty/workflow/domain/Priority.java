package duty.workflow.domain;

/**
 * Complaint priority, matching {@code ck_priority} (data model section 3.5).
 *
 * <p>Priority ages: a scheduled job escalates {@code P3 → P2 → P1} once a complaint has been open
 * past 1.5× its SLA window, so old low-priority work eventually surfaces above new noise.
 * {@code base_priority} is retained alongside so the dashboard can show "P1 (aged from P3)"
 * (state machines section 4.5).
 */
public enum Priority {
    P1,
    P2,
    P3;

    /** One step of aging. Escalation is capped at {@code P1}. */
    public Priority escalated() {
        return switch (this) {
            case P3 -> P2;
            case P2, P1 -> P1;
        };
    }
}
