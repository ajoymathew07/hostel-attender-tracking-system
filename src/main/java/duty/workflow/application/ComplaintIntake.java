package duty.workflow.application;

/**
 * The workflow module's published entry point for a brand-new complaint — transition T1 (section
 * 4.2).
 *
 * <p>Ingestion calls this and nothing else of {@code workflow}. It exists so that the rule "a
 * complaint is created in one place, under one set of defaults" survives having two sources
 * feeding it (CSV now, Sheets in PR 8) and, later, the full state machine of PR 11 taking over the
 * transition.
 *
 * <p><strong>Deliberately not annotated {@code @PreAuthorize}.</strong> The authorization decision
 * belongs to the thing a user initiates — uploading a CSV is manager-only and is checked there
 * (section 11.3). The Sheets poller of PR 9 runs on a scheduler with no principal at all, and a
 * role check here would refuse it.
 */
public interface ComplaintIntake {

    /**
     * Creates a complaint from an ingested row.
     *
     * <p>Must be called inside the caller's transaction, so that the complaint, its source row and
     * its audit entry commit or roll back together.
     */
    IngestedComplaint record(NewComplaint command);
}
