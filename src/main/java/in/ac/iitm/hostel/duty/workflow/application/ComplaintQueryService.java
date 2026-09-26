package in.ac.iitm.hostel.duty.workflow.application;

import java.util.List;

/** Read access to complaints for the manager's desktop (section 10.3). */
public interface ComplaintQueryService {

    /** Every complaint, newest report first. */
    List<ComplaintListItem> listAll();

    /** How many complaints exist — the dashboard's headline count. */
    long count();
}
