package in.ac.iitm.hostel.duty.workflow.infrastructure;

import in.ac.iitm.hostel.duty.workflow.domain.Complaint;
import in.ac.iitm.hostel.duty.workflow.domain.ComplaintStatus;
import java.time.Instant;
import java.util.Collection;
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

    long countByStatus(ComplaintStatus status);

    long countByStatusIn(Collection<ComplaintStatus> statuses);

    /** The dashboard's "resolved today" tile; the day boundary is the caller's. */
    long countByStatusAndResolvedAtAfter(ComplaintStatus status, Instant from);

    /**
     * Open complaints already past their due date — the SLA-risk tile.
     *
     * <p>Counted from {@code sla_due_at} rather than from the {@code sla_breached} flag, for the
     * same reason the list computes overdue rather than reading it: the flag is written by the
     * ageing sweep on an interval, and a manager looking at the tile wants to know about now.
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
     * Candidates for the aging and breach sweep of section 4.5, backed by {@code ix_complaint_sla}.
     *
     * <p>Scoped by status rather than by the breach flag: a complaint that was flagged on an earlier
     * run may still be due its <em>next</em> escalation step, so filtering the flagged ones out here
     * would cap aging at one step per complaint. {@code statuses} is what excludes the complaints
     * whose clock is not running — resolved, finished, or on hold.
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
