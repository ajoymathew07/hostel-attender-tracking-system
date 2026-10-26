package duty.analytics.infrastructure;

import duty.analytics.domain.AnalyticsAlert;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Detector findings, one row per {@code dedupe_key}. */
public interface AnalyticsAlertRepository extends JpaRepository<AnalyticsAlert, Long> {

    Optional<AnalyticsAlert> findByDedupeKey(String dedupeKey);

    List<AnalyticsAlert> findByAcknowledgedAtIsNullOrderByDetectedAtDesc();
}
