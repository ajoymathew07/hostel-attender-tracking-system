package duty.dispatch.infrastructure;

import duty.dispatch.domain.Floor;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** The hostel's floors. */
public interface FloorRepository extends JpaRepository<Floor, Long> {

    Optional<Floor> findByWingAndFloorNumber(String wing, int floorNumber);
}
