package duty.analytics.infrastructure;

import duty.analytics.application.AnalyticsService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The nightly anomaly scan (section 8.3), under a ShedLock so two instances never both run it.
 *
 * <p>A free-tier instance asleep at 02:30 misses the night's scan, so the analytics page also has a
 * "Run checks now" button.
 */
@Component
public class AnalyticsScheduler {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsScheduler.class);

    private final AnalyticsService analytics;

    AnalyticsScheduler(AnalyticsService analytics) {
        this.analytics = analytics;
    }

    @Scheduled(cron = "0 30 2 * * *", zone = "Asia/Kolkata")
    @SchedulerLock(name = "analyticsScan", lockAtMostFor = "10m")
    public void scan() {
        log.info("Analytics scan raised or refreshed {} alert(s)", analytics.scan());
    }
}
