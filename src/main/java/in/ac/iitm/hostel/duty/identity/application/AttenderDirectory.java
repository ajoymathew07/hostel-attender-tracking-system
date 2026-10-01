package in.ac.iitm.hostel.duty.identity.application;

import java.util.List;

/**
 * Who dispatch may consider — the candidate pool of section 6.2's eligibility filter.
 *
 * <p>Separate from {@link UserProvisioningService#listAssignableAttenders()}, which returns the
 * same people and is manager-only. Auto-dispatch runs on the Sheets poll, with no signed-in user
 * at all, so a manager check there would mean every complaint that arrived overnight went to
 * triage. This returns <strong>ids and nothing else</strong> — no name, no email, no role — so
 * opening it to the system exposes nothing a manager check was protecting (section 11.3).
 */
public interface AttenderDirectory {

    /** The ids of every active attender, lowest first. */
    List<Long> activeAttenderIds();
}
