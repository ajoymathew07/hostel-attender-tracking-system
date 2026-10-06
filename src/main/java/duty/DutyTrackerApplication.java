package duty;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** The Spring Boot entry point. Component scanning starts here, so it covers every module. */
@SpringBootApplication
public class DutyTrackerApplication {

    public static void main(String[] args) {
        SpringApplication.run(DutyTrackerApplication.class, args);
    }
}
