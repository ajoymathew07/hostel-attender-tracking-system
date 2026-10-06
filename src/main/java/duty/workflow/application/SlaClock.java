package duty.workflow.application;

import duty.settings.application.Settings;
import duty.settings.application.SettingsStore;
import duty.workflow.domain.Complaint;
import duty.workflow.domain.SlaTargets;
import duty.workflow.domain.WorkingCalendar;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Computes the SLA due date and the moment ageing escalates, so both are calculated the same way
 * everywhere.
 */
@Service
public class SlaClock {

    private final SlaSettings settings;
    private final SettingsStore store;

    SlaClock(SlaSettings settings, SettingsStore store) {
        this.settings = settings;
        this.store = store;
    }

    /**
     * Sets {@code sla_due_at} from the domain and priority, counting working hours from when the resident
     * submitted (not when ingestion noticed). An {@code UNCLASSIFIED} complaint gets no due date until a
     * manager sets its domain.
     */
    public void schedule(Complaint complaint) {
        // One read, so the table and the calendar that produce a due date are the same edit.
        Settings current = store.current();
        SlaTargets targets = settings.targetsFrom(current);
        WorkingCalendar calendar = settings.calendarFrom(current);
        targets
                .window(complaint.getDomain(), complaint.getPriority())
                .ifPresent(
                        window ->
                                complaint.scheduleSla(
                                        calendar.dueAt(complaint.getReportedAt(), window)));
    }

    /**
     * When the complaint passes 1.5× its SLA window and is due an escalation: half a window after
     * {@code sla_due_at}.
     *
     * <p>Measured from the due date because it already includes working hours and hold extensions. Uses
     * the base priority's window, so each escalation does not move the next threshold.
     */
    public Optional<Instant> ageingDueAt(Complaint complaint) {
        if (complaint.getSlaDueAt() == null) {
            return Optional.empty();
        }
        Settings current = store.current();
        WorkingCalendar calendar = settings.calendarFrom(current);
        return settings
                .targetsFrom(current)
                .window(complaint.getDomain(), complaint.getBasePriority())
                .map(window -> calendar.dueAt(complaint.getSlaDueAt(), halfOf(window)));
    }

    private static Duration halfOf(Duration window) {
        return window.dividedBy(2);
    }
}
