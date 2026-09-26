package in.ac.iitm.hostel.duty.ingestion.infrastructure;

import in.ac.iitm.hostel.duty.ingestion.domain.SourceRow;
import in.ac.iitm.hostel.duty.ingestion.domain.SourceType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * Section 5.3's watermark: the highest row index ever observed for one source.
     *
     * <p>Derived rather than stored. The rows we hold already state how far the sheet was read, so
     * a separate watermark column would be a second copy of the same fact — and one that could
     * disagree with it after a run that failed part way.
     *
     * @return {@code null} when nothing from this source has been ingested yet
     */
    @Query("select max(row.rowIndex) from SourceRow row where row.sourceType = :sourceType and row.sourceRef = :sourceRef")
    Integer maxRowIndex(@Param("sourceType") SourceType sourceType, @Param("sourceRef") String sourceRef);
}
