package in.ac.iitm.hostel.duty.dispatch.infrastructure;

import in.ac.iitm.hostel.duty.dispatch.domain.Coverage;
import in.ac.iitm.hostel.duty.dispatch.domain.FloorAssignment;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Who covers which floor (data model section 3.4). {@code ix_fa_lookup} backs the by-floor query,
 * which is the hot path for dispatch.
 */
public interface FloorAssignmentRepository extends JpaRepository<FloorAssignment, Long> {

    List<FloorAssignment> findByFloorId(Long floorId);

    List<FloorAssignment> findByFloorIdAndCoverage(Long floorId, Coverage coverage);

    List<FloorAssignment> findByAttenderId(Long attenderId);

    Optional<FloorAssignment> findByAttenderIdAndFloorId(Long attenderId, Long floorId);

    /**
     * Every coverage row with its floor, for one dispatch.
     *
     * <p>The fetch join is what keeps scoring four attenders to one query rather than one per
     * attender plus one per floor: the heuristic needs each candidate's floor <em>numbers</em> to
     * measure distance, not just the ids (section 6.2).
     */
    @Query("select fa from FloorAssignment fa join fetch fa.floor")
    List<FloorAssignment> findAllWithFloor();
}
