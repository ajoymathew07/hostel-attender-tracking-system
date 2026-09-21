package in.ac.iitm.hostel.duty.identity.application;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
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

    /**
     * {@code getIfUnique} rather than a required dependency: the {@code workflow} module supplies
     * the real lookup in a later PR, and until then — or if two ever compete — the deny-by-default
     * implementation stands in.
     */
    @Autowired
    AccessGuard(ObjectProvider<TaskAssignmentLookup> lookups) {
        this(lookups.getIfUnique(DeniesEveryTaskAssignment::new));
    }

    public AccessGuard(TaskAssignmentLookup assignments) {
        this.assignments = assignments;
    }

    /** Whether the signed-in user holds an {@code ACTIVE} assignment for this task (E20). */
    public boolean ownsTask(long taskId) {
        return CurrentUser.get()
                .map(user -> assignments.holdsActiveAssignment(user.getUserId(), taskId))
                .orElse(false);
    }

    /**
     * Whether the signed-in user <em>is</em> this user — the check behind "an attender sees their
     * own point detail, a manager sees anyone's" (section 11.3).
     */
    public boolean isSelf(long userId) {
        return CurrentUser.get().map(user -> user.getUserId() == userId).orElse(false);
    }
}
