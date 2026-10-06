package duty.workflow.application;

/**
 * The tiles across the top of the manager's dashboard (section 10.3).
 *
 * <p>Section 10.3's sketch leads with a {@code NEW} tile. There is no such tile here because there
 * is no such state to count: auto-dispatch runs inside the intake transaction, so a complaint is
 * assigned or in triage before the manager could see it. {@code TRIAGE} is what replaced it, and
 * it is the one tile that asks the manager for something.
 *
 * @param triage waiting for a manager — the only number here that is a to-do list
 * @param active somebody is on it, including work on hold
 * @param resolvedToday the day's output, in the manager's own timezone
 * @param overdue open complaints already past their due date
 */
public record DashboardCounters(long triage, long active, long resolvedToday, long overdue) {}
