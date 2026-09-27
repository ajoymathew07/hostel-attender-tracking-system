package in.ac.iitm.hostel.duty.workflow.application;

/**
 * The docket number does not match the configured pattern (transition T11's guard, OQ-1).
 *
 * <p>There is no CMS API (section 1.8), so format is all that can be validated — and the format is
 * a config property rather than a code constant, so a wrong guess costs a property change and not
 * a redeploy.
 */
public class InvalidDocketException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String docketNo;

    public InvalidDocketException(String docketNo) {
        super("Not a usable CMS docket number");
        this.docketNo = docketNo;
    }

    public String getDocketNo() {
        return docketNo;
    }
}
