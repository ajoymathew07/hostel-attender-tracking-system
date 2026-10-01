package in.ac.iitm.hostel.duty.workflow.application;

import in.ac.iitm.hostel.duty.workflow.domain.Complaint;
import in.ac.iitm.hostel.duty.workflow.domain.SlaTargets;
import in.ac.iitm.hostel.duty.workflow.domain.WorkingCalendar;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * The SLA due date, and the later moment at which aging escalates (section 4.5).
 *
 * <p>Two calls over the same two pieces of configuration — the table of section 8.2 and the working
 * calendar of OQ-6 — so that "when is this due" has one answer wherever it is asked: at intake, at
 * a manager's triage correction, and in the aging sweep.
 */
@Service
public class SlaClock {

    private final SlaTargets targets;
    private final WorkingCalendar calendar;

    SlaClock(SlaTargets targets, WorkingCalendar calendar) {
        this.targets = targets;
        this.calendar = calendar;
    }

    /**
     * Sets {@code sla_due_at} from the complaint's domain and current priority — T1's "set base
     * priority + SLA due".
     *
     * <p>Measured from {@code reportedAt}, the moment the resident pressed submit, not from when
     * ingestion happened to notice: a poll that ran three minutes late must not shorten anybody's
     * window, and a CSV uploaded the next morning must not restart it.
     *
     * <p>An {@code UNCLASSIFIED} complaint has no target (section 8.2 has no row for it) and so
     * gets no due date. It is in the triage queue, where the thing it is waiting for is a manager
     * rather than an attender; it gets a due date when it gets a domain.
     */
    public void schedule(Complaint complaint) {
        targets
                .window(complaint.getDomain(), complaint.getPriority())
                .ifPresent(
                        window ->
                                complaint.scheduleSla(
                                        calendar.dueAt(complaint.getReportedAt(), window)));
    }

    /**
     * When this complaint has been open past <strong>1.5×</strong> its SLA window, and so is due an
     * escalation (section 4.5).
     *
     * <p>Half a window <em>after</em> {@code sla_due_at} rather than one-and-a-half windows after
     * the report, for two reasons: the due date already carries the working-hours arithmetic, and it
     * already carries every extension T13 has made for hold time — so hold time is excluded from
     * aging, as section 4.5 requires, without a second subtraction that could disagree with the
     * first.
     *
     * <p>The window is the <em>base</em> priority's. Reading the current one would move the
     * threshold every time the job escalated, which would make each step's timing depend on when
     * the previous run happened to fire.
     */
    public Optional<Instant> ageingDueAt(Complaint complaint) {
        if (complaint.getSlaDueAt() == null) {
            return Optional.empty();
        }
        return targets
                .window(complaint.getDomain(), complaint.getBasePriority())
                .map(window -> calendar.dueAt(complaint.getSlaDueAt(), halfOf(window)));
    }

    private static Duration halfOf(Duration window) {
        return window.dividedBy(2);
    }
}
