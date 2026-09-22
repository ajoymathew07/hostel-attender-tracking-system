package in.ac.iitm.hostel.duty.ingestion.infrastructure;

import in.ac.iitm.hostel.duty.ingestion.domain.SourceRow;
import in.ac.iitm.hostel.duty.ingestion.domain.SourceType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Observed source rows (data model section 3.5).
 *
 * <p>The by-identity lookup below is the fast path of delta detection, but it is not the guarantee:
 * a check-then-insert is itself a race, so correctness rests on {@code uq_source_row}. A concurrent
 * second run that slips past the lookup still fails at the constraint, which is exactly the
 * behaviour test plan X6 asserts.
 */
public interface SourceRowRepository extends JpaRepository<SourceRow, Long> {

    Optional<SourceRow> findBySourceTypeAndSourceRefAndResponseKey(
            SourceType sourceType, String sourceRef, String responseKey);

    boolean existsBySourceTypeAndSourceRefAndResponseKey(
            SourceType sourceType, String sourceRef, String responseKey);

    List<SourceRow> findByContentHash(String contentHash);

    List<SourceRow> findByComplaintIdIsNull();
}
