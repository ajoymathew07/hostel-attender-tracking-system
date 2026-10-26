package duty.notification.infrastructure;

import duty.notification.domain.PushSubscription;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Push endpoints, one per browser install ({@code uq_push_endpoint}). */
public interface PushSubscriptionRepository extends JpaRepository<PushSubscription, Long> {

    Optional<PushSubscription> findByEndpoint(String endpoint);

    List<PushSubscription> findByUserIdAndRevokedAtIsNull(Long userId);
}
