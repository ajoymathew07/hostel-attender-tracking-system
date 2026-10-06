package duty.workflow.application;

/**
 * The docket number does not match the configured format. There is no Engineering Unit API, so only
 * the format can be checked.
 */
public class InvalidDocketException extends GuardFailedException {

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
