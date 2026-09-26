package in.ac.iitm.hostel.duty.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assumptions.assumeThat;

import in.ac.iitm.hostel.duty.DutyTrackerApplication;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * The two ways the owner can misconfigure a deploy, and the noise each one makes.
 *
 * <p>These start the application by hand rather than with {@code @SpringBootTest}, because what is
 * being tested is that it does <em>not</em> start. Neither case reaches a database: both fail
 * before any connection is attempted, which is the property that matters — a misconfigured start
 * must stop, not quietly talk to the wrong database.
 */
class MisconfiguredStartupTest {

    @Test
    @DisplayName("no profile named: the start fails and the message says which one to set")
    void anUnsetProfileIsNotSilentlyLocal() {
        assertThatThrownBy(
                        () ->
                                new SpringApplicationBuilder(DutyTrackerApplication.class)
                                        .web(WebApplicationType.NONE)
                                        .run())
                .hasStackTraceContaining("SPRING_PROFILES_ACTIVE")
                .hasStackTraceContaining("Exactly one of the 'local' and 'cloud' profiles");
    }

    @Test
    @DisplayName("cloud profile, DATABASE_URL absent: the start fails naming it, never falling back to localhost")
    void aMissingDatabaseUrlFailsRatherThanDefaulting() {
        // The variable is unset on a developer machine and in CI; if someone has exported it for
        // their own use this test has nothing to say, so it steps aside rather than failing.
        assumeThat(System.getenv("DATABASE_URL")).isNull();

        Throwable thrown =
                catchThrowable(
                        () ->
                                new SpringApplicationBuilder(DutyTrackerApplication.class)
                                        .web(WebApplicationType.NONE)
                                        .profiles("cloud")
                                        // Everything the cloud profile requires except the one
                                        // under test, so the failure can only be about that one.
                                        .properties(
                                                "DATABASE_USERNAME=irrelevant",
                                                "DATABASE_PASSWORD=irrelevant",
                                                "GOOGLE_CLIENT_ID=irrelevant",
                                                "GOOGLE_CLIENT_SECRET=irrelevant")
                                        .run());

        assertThat(thrown).isNotNull();
        assertThat(stackTraceOf(thrown))
                .contains("DATABASE_URL")
                .contains("docs/runbook/deploy.md")
                // The `local` profile's fallback must be nowhere in sight: the start is refused
                // before a datasource exists, not after a failed connection to this machine.
                .doesNotContain("localhost");
    }

    private static String stackTraceOf(Throwable thrown) {
        StringWriter out = new StringWriter();
        thrown.printStackTrace(new PrintWriter(out));
        return out.toString();
    }
}
