package in.ac.iitm.hostel.duty.workflow.infrastructure;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.time.ZoneId;
import org.springframework.stereotype.Component;

/**
 * Allocates {@code public_ref} — {@code CAU-{year}-{zero-padded sequence}} (section 5.4).
 *
 * <p>A database sequence rather than {@code max(public_ref) + 1}. Two ingestion runs, or a poll
 * racing a CSV upload, would read the same maximum and allocate the same reference; the second
 * would then fail on {@code uq_complaint_ref} and lose a complaint that was otherwise fine.
 * {@code nextval} is atomic and does not participate in the transaction, so a rolled-back row
 * burns a number instead of colliding — a gap in the sequence is invisible to everyone, a
 * duplicate reference is not.
 *
 * <p>The sequence is global rather than per year, and the year is a prefix. A per-year sequence
 * would need a reset at midnight on the 31st of December, run by something, correctly, once —
 * which is a moving part bought for cosmetics, since {@code uq_complaint_ref} only needs the whole
 * string to be unique.
 */
@Component
public class ComplaintReferenceGenerator {

    /** References are read by humans in Chennai; the business date is Asia/Kolkata (NFR-7). */
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Kolkata");

    private static final String NEXT_VALUE = "select nextval('complaint_ref_seq')";

    @PersistenceContext private EntityManager entityManager;

    /** The next reference, for a complaint reported at the given instant. */
    public String next(Instant reportedAt) {
        Number sequence = (Number) entityManager.createNativeQuery(NEXT_VALUE).getSingleResult();
        int year = reportedAt.atZone(BUSINESS_ZONE).getYear();
        return "CAU-%d-%04d".formatted(year, sequence.longValue());
    }
}
