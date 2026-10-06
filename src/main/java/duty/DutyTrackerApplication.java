package duty;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of the modular monolith (architecture section 2.1).
 *
 * <p>Component scanning starts at this package, so every module under
 * {@code duty} is part of the one deployable jar.
 */
@SpringBootApplication
public class DutyTrackerApplication {

    public static void main(String[] args) {
        SpringApplication.run(DutyTrackerApplication.class, args);
    }
}
