package in.ac.iitm.hostel.duty.workflow.application;

/**
 * The docket gate (transitions T11 and T14, state machines section 4.3).
 *
 * <p>You cannot collect +2 points for a complaint you never filed with the Engineering Unit. This
 * is the mechanism that makes institutional traceability structurally enforced rather than a
 * matter of attender diligence, so it is a refusal in the service and not a disabled button on a
 * screen.
 */
public class DocketRequiredException extends GuardFailedException {

    private static final long serialVersionUID = 1L;

    public DocketRequiredException(long taskId) {
        super("Task " + taskId + " has no CMS docket; it cannot be resolved for points");
    }
}
