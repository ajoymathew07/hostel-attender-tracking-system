package in.ac.iitm.hostel.duty.workflow.application;

/**
 * The {@code payload} of {@code transition(complaintId, event, payload)} (section 4.1): the few
 * values a guard or a side effect needs that are not already on the aggregate.
 *
 * <p>One record with three nullable fields rather than a type per event. Across seventeen rows the
 * table asks for exactly three things — a reason, a docket number, an attender — and a hierarchy
 * of payload types would be fifteen classes to express what these three fields say.
 *
 * @param reason the mandatory reason of T5, T8, T10, T12 and T17
 * @param docketNo the number the Engineering Unit gave back (T11)
 * @param attenderId who the work is going to (T2, T4, T6)
 */
public record TransitionPayload(String reason, String docketNo, Long attenderId) {

    /** For the rows whose guard column is a dash. */
    public static TransitionPayload none() {
        return new TransitionPayload(null, null, null);
    }

    public static TransitionPayload reason(String reason) {
        return new TransitionPayload(reason, null, null);
    }

    public static TransitionPayload docket(String docketNo) {
        return new TransitionPayload(null, docketNo, null);
    }

    public static TransitionPayload attender(long attenderId) {
        return new TransitionPayload(null, null, attenderId);
    }
}
