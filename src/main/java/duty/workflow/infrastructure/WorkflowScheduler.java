package duty.workflow.infrastructure;

import duty.workflow.application.PriorityService;
import duty.workflow.application.SpecialDutyService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The workflow's scheduled jobs: priority ageing and special-duty expiry.
 *
 * <p>{@code @SchedulerLock} is essential: if two instances ran a job at once, a complaint would be
 * escalated twice in one tick. Each job waits one interval after startup. The methods return void
 * because ShedLock cannot invent a result when the lock is held; the outcome is in the audit log.
 */
@Component
public class WorkflowScheduler {

    private static final Logger log = LoggerFactory.getLogger(WorkflowScheduler.class);

    private final PriorityService priorities;
    private final SpecialDutyService duties;

    WorkflowScheduler(PriorityService priorities, SpecialDutyService duties) {
        this.priorities = priorities;
        this.duties = duties;
    }

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

    /** Closes duties whose claiming window has passed. Same interval as ageing; it is one cheap query. */
    @Scheduled(
            fixedDelayString = "${workflow.sla.ageing-interval}",
            initialDelayString = "${workflow.sla.ageing-interval}")
    @SchedulerLock(name = "dutyExpiry", lockAtMostFor = "5m")
    public void expireDuties() {
        int expired = duties.expireOverdue();
        if (expired > 0) {
            log.info("Expired {} special dut(y/ies) past their closing time", expired);
        }
    }
}
