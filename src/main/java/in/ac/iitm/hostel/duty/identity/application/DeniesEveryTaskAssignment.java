package in.ac.iitm.hostel.duty.identity.application;

/**
 * The default {@link TaskAssignmentLookup}, in force until the {@code workflow} module publishes a
 * real one.
 *
 * <p>Deny-by-default rather than allow-by-default, because the failure it guards against — an
 * attender acting on another attender's task and collecting the points (section 11.3, E20) — is
 * silent, while an over-strict 403 during development is not.
 */
public final class DeniesEveryTaskAssignment implements TaskAssignmentLookup {

    @Override
    public boolean holdsActiveAssignment(long userId, long taskId) {
        return false;
    }
}
