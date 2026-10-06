package duty.dispatch.infrastructure;

import duty.dispatch.domain.AvailabilityWindow;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Attenders' availability windows. */
public interface AvailabilityWindowRepository extends JpaRepository<AvailabilityWindow, Long> {

    List<AvailabilityWindow> findByAttenderIdOrderByStartsAtDesc(Long attenderId);

    /** Windows in force at an instant; an open-ended window has no end. */
    @Query(
            """
            select w from AvailabilityWindow w
             where w.attenderId = :attenderId
               and w.startsAt <= :at
               and (w.endsAt is null or w.endsAt > :at)
             order by w.startsAt desc
            """)
    List<AvailabilityWindow> findInForce(@Param("attenderId") Long attenderId, @Param("at") Instant at);
}
