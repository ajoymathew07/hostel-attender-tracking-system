package duty.ingestion.infrastructure;

import duty.ingestion.domain.IngestionRun;
import duty.ingestion.domain.IngestionStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Ingestion run history (data model section 3.5). */
public interface IngestionRunRepository extends JpaRepository<IngestionRun, Long> {

    List<IngestionRun> findByStatusOrderByStartedAtDesc(IngestionStatus status);

    List<IngestionRun> findTop20ByOrderByStartedAtDesc();
}
