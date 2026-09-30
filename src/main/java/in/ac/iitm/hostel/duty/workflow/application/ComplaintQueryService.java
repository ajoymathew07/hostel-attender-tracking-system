package in.ac.iitm.hostel.duty.workflow.application;

import java.util.List;

/** Read access to complaints for the manager's desktop (section 10.3). */
public interface ComplaintQueryService {

    /** Every complaint, newest report first. */
    List<ComplaintListItem> listAll();

    /**
     * The triage queue: complaints the classifier could not confidently place (sections 5.5,
     * 10.3).
     *
     * <p><strong>Oldest first</strong>, unlike {@link #listAll()}. This is a work queue rather
     * than a news feed — the complaint that has been waiting longest is the one to settle next —
     * and it is the only part of the dashboard that requires the manager's action.
     */
    List<ComplaintListItem> listTriage();

    /** How many complaints exist — the dashboard's headline count. */
    long count();
}
