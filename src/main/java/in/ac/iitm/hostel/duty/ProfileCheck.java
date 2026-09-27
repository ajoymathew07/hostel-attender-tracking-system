package in.ac.iitm.hostel.duty;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.util.StringUtils;

/**
 * Refuses to start unless exactly one of {@code local} and {@code cloud} is active, and — under
 * {@code cloud} — unless every environment variable that profile needs is actually set.
 *
 * <p>This exists because of one specific way a deployment goes wrong. {@code application.yml} used
 * to declare {@code spring.profiles.default: local}, so a Render service whose {@code
 * SPRING_PROFILES_ACTIVE} was never set started anyway — with the developer profile. That profile
 * has a placeholder OAuth client, no {@code Secure} on the session cookie, and a datasource that
 * reads {@code DATABASE_URL} but falls back to {@code localhost}. The failure mode is therefore not
 * a crash but a running application, configured wrongly, possibly pointed at a database nobody
 * intended. An explicit choice with a loud failure is worth more here than a convenient default.
 *
 * <p>The variable check exists for the same reason. Spring Boot's relaxed binding leaves an
 * unresolvable {@code ${DATABASE_URL}} as the literal text, so a missing variable surfaces much
 * later as {@code URL must start with 'jdbc'} — true, and useless to whoever is reading the deploy
 * log. Naming the variables that are missing turns that into a one-line fix.
 *
 * <p>It runs as an {@link EnvironmentPostProcessor} (registered in {@code
 * META-INF/spring.factories}) because that is the last point before any bean — and therefore any
 * datasource or connection — is created, and it sees the profiles from every source: the
 * environment variable, {@code --spring.profiles.active} and {@code @ActiveProfiles} in tests.
 */
public class ProfileCheck implements EnvironmentPostProcessor {

    /** The two profiles of architecture section 2.7; there are deliberately no others. */
    private static final Set<String> KNOWN = Set.of("local", "cloud");

    /**
     * Every variable the {@code cloud} profile reads without a default. The list is the same one
     * the runbook tells the owner to set, and keeping them in step is the point of checking here.
     */
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
