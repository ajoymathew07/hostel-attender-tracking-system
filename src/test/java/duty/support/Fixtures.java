package duty.support;

import duty.identity.application.SignedInUser;
import duty.identity.domain.AppUser;
import duty.identity.domain.UserRole;
import duty.ingestion.domain.IngestionRun;
import duty.ingestion.domain.SourceType;
import duty.ingestion.domain.TriggerType;
import duty.workflow.domain.ClassificationMethod;
import duty.workflow.domain.Complaint;
import duty.workflow.domain.ComplaintDomain;
import duty.workflow.domain.ComplaintStatus;
import duty.workflow.domain.Priority;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Valid test data, with every identifier unique per call, since test classes share one database.
 */
public final class Fixtures {

    private static final AtomicLong SEQUENCE = new AtomicLong();

    private Fixtures() {}

    /** A value unique within this JVM run. */
    public static String unique(String prefix) {
        return prefix + "-" + SEQUENCE.incrementAndGet();
    }

    /** A 64-character hex digest, as {@code source_row} requires. */
    public static String hash(String seed) {
        StringBuilder digest = new StringBuilder(Integer.toHexString(seed.hashCode()));
        while (digest.length() < 64) {
            digest.append('0');
        }
        return digest.substring(0, 64);
    }

    /**
     * Puts a real {@link SignedInUser} in the security context. {@code @WithMockUser} has no user id,
     * which many rules need.
     */
    public static TestingAuthenticationToken signIn(AppUser user) {
        TestingAuthenticationToken token =
                new TestingAuthenticationToken(
                        new SignedInUser(
                                user.getId(), user.getEmail(), user.getDisplayName(), user.getRole()),
                        null,
                        "ROLE_" + user.getRole().name());
        SecurityContextHolder.getContext().setAuthentication(token);
        return token;
    }

    public static AppUser attender() {
        return new AppUser(
                "Attender " + SEQUENCE.incrementAndGet(),
                UserRole.ATTENDER,
                unique("attender") + "@example.com",
                Instant.parse("2026-01-01T00:00:00Z"),
                null);
    }

    public static AppUser manager() {
        return new AppUser(
                "Manager " + SEQUENCE.incrementAndGet(),
                UserRole.MANAGER,
                unique("manager") + "@example.com",
                Instant.parse("2026-01-01T00:00:00Z"),
                null);
    }

    public static IngestionRun ingestionRun() {
        return new IngestionRun(
                SourceType.GOOGLE_SHEETS,
                TriggerType.SCHEDULED,
                Instant.parse("2026-02-01T06:03:00Z"),
                null);
    }

    /** A complaint in the triage queue, where manual assignment (T4) starts. */
    public static Complaint complaintInTriage() {
        Complaint complaint = complaint();
        complaint.moveTo(ComplaintStatus.TRIAGE, Instant.parse("2026-02-01T06:03:00Z"));
        return complaint;
    }

    public static Complaint complaint() {
        return new Complaint(
                unique("CAU-2026"),
                "No water in the bathroom since morning",
                Instant.parse("2026-02-01T05:30:00Z"),
                ComplaintDomain.RO_PLANT,
                ClassificationMethod.REPORTED,
                Priority.P2,
                Instant.parse("2026-02-01T06:03:00Z"));
    }
}
