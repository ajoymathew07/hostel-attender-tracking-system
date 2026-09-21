package in.ac.iitm.hostel.duty;

import static org.assertj.core.api.Assertions.assertThat;

import in.ac.iitm.hostel.duty.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/** The application context starts against a real PostgreSQL container. */
@SpringBootTest
class DutyTrackerApplicationTests extends AbstractIntegrationTest {

    @Autowired private ApplicationContext context;

    @Test
    @DisplayName("the Spring context loads with the application as its entry point")
    void contextLoads() {
        assertThat(context).isNotNull();
        assertThat(context.getBean(DutyTrackerApplication.class)).isNotNull();
    }
}
