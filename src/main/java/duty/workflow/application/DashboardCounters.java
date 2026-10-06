package duty.workflow.application;

/**
 * The counts across the top of the manager's dashboard.
 *
 * <p>No "new" count: auto-dispatch runs during intake, so a complaint is already assigned or in triage
 * when the manager first sees it.
 *
 * @param triage waiting for the manager
 * @param active someone is on it, including work on hold
 * @param resolvedToday resolved today, in local time
 * @param overdue open complaints past their due date
 */
public record DashboardCounters(long triage, long active, long resolvedToday, long overdue) {}
