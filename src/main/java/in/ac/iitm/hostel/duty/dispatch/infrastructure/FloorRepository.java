package in.ac.iitm.hostel.duty.dispatch.infrastructure;

import in.ac.iitm.hostel.duty.dispatch.domain.Floor;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Floors, identified by {@code uq_floor (wing, floor_number)} (data model section 3.4). */
public interface FloorRepository extends JpaRepository<Floor, Long> {

    Optional<Floor> findByWingAndFloorNumber(String wing, int floorNumber);
}
