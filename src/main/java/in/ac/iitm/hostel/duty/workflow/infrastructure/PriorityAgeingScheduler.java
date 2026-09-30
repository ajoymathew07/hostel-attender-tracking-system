package in.ac.iitm.hostel.duty.workflow.infrastructure;

import in.ac.iitm.hostel.duty.workflow.application.PriorityService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The aging job of section 4.5: old low-priority work surfaces above new noise.
 *
 * <p><strong>{@code @SchedulerLock} is not optional here.</strong> Unlike ingestion, aging has no
 * unique constraint behind it to make a double run harmless — two instances sweeping at once would
 * each escalate the same complaint one step, and a P3 would arrive at P1 in a single tick with two
 * audit rows claiming to be the first. The lock is a row in the one database read with the
 * <em>database's</em> clock (section 2.5, point 4), which is the point: two instances are exactly
 * the situation in which two clocks disagree.
 *
 * <p>The interval is far finer than the SLA windows it polices, so the escalation is late by at most
 * one tick. The first run is one interval after start rather than at start, because a restart loop
 * on the free tier would otherwise sweep continuously.
 */
@Component
public class PriorityAgeingScheduler {

    private static final Logger log = LoggerFactory.getLogger(PriorityAgeingScheduler.class);

    private final PriorityService priorities;

    PriorityAgeingScheduler(PriorityService priorities) {
        this.priorities = priorities;
    }

    /**
     * Void by necessity: ShedLock's proxy can only lock a method whose result it does not have to
     * invent when the lock is already held. What the sweep did is in {@code audit_event}, which is
     * where an unattended job's outcome belongs.
     */
    @Scheduled(
            fixedDelayString = "${workflow.sla.ageing-interval}",
            initialDelayString = "${workflow.sla.ageing-interval}")
    @SchedulerLock(name = "priorityAgeing", lockAtMostFor = "5m")
    public void age() {
        int escalated = priorities.ageOpenComplaints();
        if (escalated > 0) {
            log.info("Aging escalated {} complaint(s) past 1.5x their SLA window", escalated);
        }
    }
}
