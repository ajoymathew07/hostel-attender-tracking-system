package duty.workflow.application;

/**
 * Extra values a transition may need: a reason, a docket number, or an attender. One record with
 * nullable fields rather than a class per event.
 *
 * @param reason required for reject, release, no-defect, block and reopen
 * @param docketNo the Engineering Unit's number (T11)
 * @param attenderId who the work goes to (T2, T4, T6)
 */
public record TransitionPayload(String reason, String docketNo, Long attenderId) {

    /** No extra values. */
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
