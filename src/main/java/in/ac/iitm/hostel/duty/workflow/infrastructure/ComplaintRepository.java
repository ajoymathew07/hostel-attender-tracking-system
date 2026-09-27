package in.ac.iitm.hostel.duty.workflow.infrastructure;

import in.ac.iitm.hostel.duty.workflow.domain.Complaint;
import in.ac.iitm.hostel.duty.workflow.domain.ComplaintStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Complaints (data model section 3.5). */
public interface ComplaintRepository extends JpaRepository<Complaint, Long> {

    Optional<Complaint> findByPublicRef(String publicRef);

    Optional<Complaint> findByDocketNo(String docketNo);

    List<Complaint> findByStatusOrderByReportedAtAsc(ComplaintStatus status);

    /** Backed by {@code ix_complaint_sla}: candidates for the aging and breach sweep. */
    @Query(
            """
            select c from Complaint c
             where c.slaBreached = false
               and c.slaDueAt is not null
               and c.slaDueAt < :at
            """)
    List<Complaint> findOverdue(@Param("at") Instant at);
}
