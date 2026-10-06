package duty.workflow.infrastructure;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.time.ZoneId;
import org.springframework.stereotype.Component;

/**
 * Creates {@code public_ref}: {@code CAU-{year}-{zero-padded number}}.
 *
 * <p>Uses a database sequence rather than {@code max + 1}, so two concurrent runs can never get the same
 * number. One sequence for all years, so nothing needs resetting on 1 January.
 */
@Component
public class ComplaintReferenceGenerator {

    /** The year in the reference is the Asia/Kolkata year. */
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
