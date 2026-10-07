package duty.ingestion.infrastructure;

import duty.ingestion.domain.SourceRow.SourceType;
import duty.ingestion.domain.SourceRow;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Observed source rows. The lookup by identity is a fast path; the unique constraint is what really
 * prevents duplicates.
 */
public interface SourceRowRepository extends JpaRepository<SourceRow, Long> {

    Optional<SourceRow> findBySourceTypeAndSourceRefAndResponseKey(
            SourceType sourceType, String sourceRef, String responseKey);

    boolean existsBySourceTypeAndSourceRefAndResponseKey(
            SourceType sourceType, String sourceRef, String responseKey);

    List<SourceRow> findByContentHash(String contentHash);

    List<SourceRow> findByComplaintIdIsNull();

    /**
     * The highest row index held for one source: where the next read starts.
     *
     * @return {@code null} when nothing from this source has been ingested yet
     */
    @Query("select max(row.rowIndex) from SourceRow row where row.sourceType = :sourceType and row.sourceRef = :sourceRef")
    Integer maxRowIndex(@Param("sourceType") SourceType sourceType, @Param("sourceRef") String sourceRef);
}
