package duty.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assumptions.assumeThat;

import duty.DutyTrackerApplication;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * The app refuses to start when misconfigured (no profile, or missing cloud variables), before
 * touching any database.
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
