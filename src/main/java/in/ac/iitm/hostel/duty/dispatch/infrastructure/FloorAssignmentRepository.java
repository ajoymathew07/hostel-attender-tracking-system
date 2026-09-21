package in.ac.iitm.hostel.duty.dispatch.infrastructure;

import in.ac.iitm.hostel.duty.dispatch.domain.Coverage;
import in.ac.iitm.hostel.duty.dispatch.domain.FloorAssignment;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Who covers which floor (data model section 3.4). {@code ix_fa_lookup} backs the by-floor query,
 * which is the hot path for dispatch.
 */
public interface FloorAssignmentRepository extends JpaRepository<FloorAssignment, Long> {

    List<FloorAssignment> findByFloorId(Long floorId);

    List<FloorAssignment> findByFloorIdAndCoverage(Long floorId, Coverage coverage);

    List<FloorAssignment> findByAttenderId(Long attenderId);

    Optional<FloorAssignment> findByAttenderIdAndFloorId(Long attenderId, Long floorId);
}
