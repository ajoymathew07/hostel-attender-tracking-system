package duty.workflow.infrastructure;

import duty.workflow.application.PriorityService;
import duty.workflow.application.SpecialDutyService;
import duty.workflow.application.WorkflowService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The workflow's scheduled jobs: priority ageing, special-duty expiry and auto-close.
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
    private final WorkflowService workflow;

    WorkflowScheduler(PriorityService priorities, SpecialDutyService duties, WorkflowService workflow) {
        this.priorities = priorities;
        this.duties = duties;
        this.workflow = workflow;
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

    /** Closes complaints resolved more than seven days ago that the manager never signed off (T16). */
    @Scheduled(
            fixedDelayString = "${workflow.sla.ageing-interval}",
            initialDelayString = "${workflow.sla.ageing-interval}")
    @SchedulerLock(name = "autoClose", lockAtMostFor = "5m")
    public void autoClose() {
        int closed = workflow.autoCloseResolved();
        if (closed > 0) {
            log.info("Auto-closed {} complaint(s) resolved more than seven days ago", closed);
        }
    }
}
