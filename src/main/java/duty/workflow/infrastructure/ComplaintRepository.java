package duty.workflow.infrastructure;

import duty.workflow.domain.Complaint;
import duty.workflow.domain.ComplaintStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Complaints. */
public interface ComplaintRepository extends JpaRepository<Complaint, Long> {

    Optional<Complaint> findByPublicRef(String publicRef);

    Optional<Complaint> findByDocketNo(String docketNo);

    List<Complaint> findByStatusOrderByReportedAtAsc(ComplaintStatus status);

    long countByStatus(ComplaintStatus status);

    long countByStatusIn(Collection<ComplaintStatus> statuses);

    /** Complaints resolved since {@code from}: the "resolved today" tile. */
    long countByStatusAndResolvedAtAfter(ComplaintStatus status, Instant from);

    /**
     * Open complaints past their due date, counted against now rather than the periodically updated
     * breach flag.
     */
    @Query(
            """
            select count(c) from Complaint c
             where c.status in :statuses
               and c.slaDueAt is not null
               and c.slaDueAt < :at
            """)
    long countOverdue(
            @Param("statuses") Collection<ComplaintStatus> statuses, @Param("at") Instant at);

    /**
     * Complaints past their due date in the given statuses, for the ageing job. Not filtered by the breach
     * flag, because a flagged complaint may still be due its next escalation.
     */
    @Query(
            """
            select c from Complaint c
             where c.status in :statuses
               and c.slaDueAt is not null
               and c.slaDueAt < :at
            """)
    List<Complaint> findByStatusInAndSlaDueAtBefore(
            @Param("statuses") Collection<ComplaintStatus> statuses, @Param("at") Instant at);
}
