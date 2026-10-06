package duty.workflow.application;

/**
 * Refuses to resolve a complaint that has no Engineering Unit docket number. No docket, no points.
 */
public class DocketRequiredException extends GuardFailedException {

    private static final long serialVersionUID = 1L;

    public DocketRequiredException(long taskId) {
        super("Task " + taskId + " has no CMS docket; it cannot be resolved for points");
    }
}
