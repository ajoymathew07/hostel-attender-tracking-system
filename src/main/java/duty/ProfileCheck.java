package duty;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.util.StringUtils;

/**
 * Refuses to start unless exactly one of the {@code local} and {@code cloud} profiles is active,
 * and, under {@code cloud}, unless every required environment variable is set.
 *
 * <p>Why: without it, a deployment with no profile set would quietly start with the developer
 * settings, and a missing variable would fail later with an unhelpful message. Runs as an
 * {@link EnvironmentPostProcessor} (see {@code META-INF/spring.factories}), before any bean is
 * created.
 */
public class ProfileCheck implements EnvironmentPostProcessor {

    /** The only two profiles. */
    private static final Set<String> KNOWN = Set.of("local", "cloud");

    /** Variables the {@code cloud} profile needs; the same list the deploy runbook gives. */
    private static final List<String> REQUIRED_IN_CLOUD =
            List.of(
                    "DATABASE_URL",
                    "DATABASE_USERNAME",
                    "DATABASE_PASSWORD",
                    "GOOGLE_CLIENT_ID",
                    "GOOGLE_CLIENT_SECRET");

    private static final String HOW_TO_FIX =
            "Start the application with exactly one of them: SPRING_PROFILES_ACTIVE=cloud on Render"
                    + " and on the manager's standby jar (see docs/runbook/deploy.md), or"
                    + " SPRING_PROFILES_ACTIVE=local for development. There is no default profile"
                    + " on purpose, so that a cloud deployment cannot silently start with developer"
                    + " settings and the wrong database.";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication app) {
        List<String> chosen =
                Arrays.stream(environment.getActiveProfiles()).filter(KNOWN::contains).toList();
        if (chosen.size() != 1) {
            throw new IllegalStateException(
                    "Exactly one of the 'local' and 'cloud' profiles must be active, but the active"
                            + " profiles are "
                            + Arrays.toString(environment.getActiveProfiles())
                            + ". "
                            + HOW_TO_FIX);
        }
        if (chosen.contains("cloud")) {
            List<String> missing =
                    REQUIRED_IN_CLOUD.stream()
                            .filter(name -> !StringUtils.hasText(environment.getProperty(name)))
                            .toList();
            if (!missing.isEmpty()) {
                throw new IllegalStateException(
                        "The 'cloud' profile requires these environment variables and they are not"
                                + " set: "
                                + String.join(", ", missing)
                                + ". Set them on the Render service, or in the shell that starts"
                                + " the standby jar — docs/runbook/deploy.md lists where each"
                                + " value comes from. Nothing is defaulted on purpose: a default"
                                + " here would mean running against the wrong database.");
            }
        }
    }
}
