package duty.identity.application;

import org.springframework.stereotype.Component;

/**
 * The ownership half of the authorization matrix, exposed to {@code @PreAuthorize} as the bean
 * {@code @access} (section 11.3).
 *
 * <p>Role is not enough for a task action. A service method that acts on a task is annotated
 *
 * <pre>{@code @PreAuthorize("@access.ownsTask(#taskId)")}</pre>
 *
 * and <em>not</em> {@code hasRole('ATTENDER')}, because "any attender" is precisely the wrong
 * answer: it would let one attender resolve another's work and collect the +2. A manager reaches a
 * task through {@code act-as} (section 9.4), which performs the identical transition as the
 * attender who holds the assignment, so it satisfies this check for the same reason the attender
 * does — the manager is the typist, not the earner.
 *
 * <p>Method security is deliberately used rather than a URL rule: the matrix is enforced at the
 * service layer, so a second controller, a scheduled job or a future API reaching the same service
 * inherits the check instead of having to remember it.
 */
@Component("access")
public class AccessGuard {

    private final TaskAssignmentLookup assignments;

    public AccessGuard(TaskAssignmentLookup assignments) {
        this.assignments = assignments;
    }

    /** Whether the signed-in user holds an {@code ACTIVE} assignment for this task (E20). */
    public boolean ownsTask(long taskId) {
        return CurrentUser.get()
                .map(user -> assignments.holdsActiveAssignment(user.getUserId(), taskId))
                .orElse(false);
    }
}
