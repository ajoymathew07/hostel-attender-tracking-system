package in.ac.iitm.hostel.duty.support;

import in.ac.iitm.hostel.duty.identity.application.SignedInUser;
import in.ac.iitm.hostel.duty.identity.domain.AppUser;
import in.ac.iitm.hostel.duty.identity.domain.UserRole;
import in.ac.iitm.hostel.duty.ingestion.domain.IngestionRun;
import in.ac.iitm.hostel.duty.ingestion.domain.SourceType;
import in.ac.iitm.hostel.duty.ingestion.domain.TriggerType;
import in.ac.iitm.hostel.duty.workflow.domain.ClassificationMethod;
import in.ac.iitm.hostel.duty.workflow.domain.Complaint;
import in.ac.iitm.hostel.duty.workflow.domain.ComplaintDomain;
import in.ac.iitm.hostel.duty.workflow.domain.Priority;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Valid rows for tests, so each test states only the thing it is actually about.
 *
 * <p>Every identifier is made unique per call. Tests share one PostgreSQL container and do not
 * truncate between classes, so a fixed {@code public_ref} or email would collide on a unique
 * constraint and produce a failure that looked like the constraint under test failing.
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
     * A real {@link SignedInUser} in the security context.
     *
     * <p>{@code @WithMockUser} is not enough for anything that reads {@code CurrentUser}: its
     * principal is Spring's own {@code User}, so {@code CurrentUser.get()} returns empty and the
     * caller has a role but no identity. Every rule in this PR — whose queue, who assigned, who
     * owns the task — needs the id, so the tests sign in a provisioned row instead.
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

    public static Complaint complaint() {
        return new Complaint(
                unique("CAU-2026"),
                "No water in the bathroom since morning",
                Instant.parse("2026-02-01T05:30:00Z"),
                ComplaintDomain.RO_PLANT,
                ClassificationMethod.RULES,
                Priority.P2,
                Instant.parse("2026-02-01T06:03:00Z"));
    }
}
