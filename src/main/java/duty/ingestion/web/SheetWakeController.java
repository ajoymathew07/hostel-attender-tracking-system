package duty.ingestion.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration.APPLICATION_TASK_EXECUTOR_BEAN_NAME;

import duty.ingestion.application.SheetsIngestionService;
import java.security.MessageDigest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * The endpoint Apps Script calls when a Form response arrives, which also wakes a sleeping free-tier
 * instance.
 *
 * <p>The request carries no data; it just triggers a normal sync. The caller is a Google server with no
 * session, so it proves itself with a shared secret header instead. No secret configured means every
 * call is refused. Returns {@code 202} immediately and syncs in the background, because the sync can
 * outlast Apps Script's timeout.
 */
@RestController
class SheetWakeController {

    private static final Logger log = LoggerFactory.getLogger(SheetWakeController.class);

    /** Also listed in {@code SecurityConfig} as session-free. */
    private static final String PATH = "/api/ingestion/wake";

    /** A header, not a query parameter, because query strings end up in access logs. */
    private static final String SECRET_HEADER = "X-Wake-Secret";

    private final SheetsIngestionService sheets;
    private final TaskExecutor executor;

    /** {@code null} when no secret is configured, which closes the endpoint. */
    private final byte[] secret;

    SheetWakeController(
            SheetsIngestionService sheets,
            @Qualifier(APPLICATION_TASK_EXECUTOR_BEAN_NAME) TaskExecutor executor,
            @Value("${ingestion.sheets.wake-secret:}") String secret) {
        this.sheets = sheets;
        this.executor = executor;
        this.secret = secret.isBlank() ? null : secret.getBytes(UTF_8);
    }

    @PostMapping(PATH)
    ResponseEntity<Void> wake(
            @RequestHeader(name = SECRET_HEADER, required = false) String presented) {
        if (!authorised(presented)) {
            // No detail, here or on the wire: a refused caller learns only that it was refused.
            log.warn("Refused a wake-up call: the shared secret did not match");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        executor.execute(sheets::wake);
        return ResponseEntity.accepted().build();
    }

    /**
     * Compares in constant time ({@link MessageDigest#isEqual}), so response timing cannot leak the
     * secret.
     */
    private boolean authorised(String presented) {
        return secret != null
                && presented != null
                && MessageDigest.isEqual(secret, presented.getBytes(UTF_8));
    }
}
