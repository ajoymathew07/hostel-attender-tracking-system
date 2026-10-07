package duty.dispatch.infrastructure;

import duty.dispatch.domain.FloorAssignment.Coverage;
import duty.dispatch.domain.FloorAssignment;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Who covers which floor. */
public interface FloorAssignmentRepository extends JpaRepository<FloorAssignment, Long> {

    List<FloorAssignment> findByFloorId(Long floorId);

    List<FloorAssignment> findByFloorIdAndCoverage(Long floorId, Coverage coverage);

    List<FloorAssignment> findByAttenderId(Long attenderId);

    Optional<FloorAssignment> findByAttenderIdAndFloorId(Long attenderId, Long floorId);

    /**
     * Every coverage row with its floor, in one query (the fetch join avoids a query per attender).
     */
    @Query("select fa from FloorAssignment fa join fetch fa.floor")
    List<FloorAssignment> findAllWithFloor();
}
