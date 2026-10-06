package duty.workflow.infrastructure;

import duty.workflow.application.PriorityService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the ageing job on a schedule.
 *
 * <p>{@code @SchedulerLock} is essential: if two instances ran it at once, a complaint would be
 * escalated twice in one tick. The first run waits one interval after startup.
 */
@Component
public class PriorityAgeingScheduler {

    private static final Logger log = LoggerFactory.getLogger(PriorityAgeingScheduler.class);

    private final PriorityService priorities;

    PriorityAgeingScheduler(PriorityService priorities) {
        this.priorities = priorities;
    }

    /**
     * Returns void because ShedLock cannot invent a result when the lock is held. The outcome is in the
     * audit log.
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
