package in.ac.iitm.hostel.duty.notification.infrastructure;

import in.ac.iitm.hostel.duty.notification.domain.PushSubscription;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Web Push endpoints (data model section 3.7).
 *
 * <p>The lookup by endpoint is what turns a browser re-subscription into an update of the existing
 * row rather than a duplicate (test plan E27); {@code uq_push_endpoint} enforces it regardless.
 */
public interface PushSubscriptionRepository extends JpaRepository<PushSubscription, Long> {

    Optional<PushSubscription> findByEndpoint(String endpoint);

    List<PushSubscription> findByUserIdAndRevokedAtIsNull(Long userId);
}
