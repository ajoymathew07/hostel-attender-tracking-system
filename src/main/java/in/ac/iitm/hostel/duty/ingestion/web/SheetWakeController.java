package in.ac.iitm.hostel.duty.ingestion.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration.APPLICATION_TASK_EXECUTOR_BEAN_NAME;

import in.ac.iitm.hostel.duty.ingestion.application.SheetsIngestionService;
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
 * The Apps Script wake-up trigger (section 2.5, point 1).
 *
 * <p>A script on the response sheet posts here whenever a Form response arrives, which is what
 * lets the hosting platform wake a sleeping free-tier instance. <strong>The request carries no row
 * data</strong> — it signals only that the sheet changed, so the normal watermark delta detection
 * does the real work and a duplicate trigger, a trigger for a row we already hold and a trigger
 * that never arrives all converge on the same computation (section 5.3).
 *
 * <p><strong>Why this is the one endpoint outside the sign-in filter.</strong> The caller is a
 * Google server, not a person: it has no session, cannot obtain one, and cannot carry a CSRF
 * token. So it is authenticated the only way such a caller can be — a shared secret in a header,
 * set as an environment variable and compared in constant time (section 11.6). It is exempted in
 * {@code SecurityConfig} by path; nothing else is.
 *
 * <p><strong>Unset means closed.</strong> With no {@code INGESTION_WAKE_SECRET} there is no secret
 * to match, so every call is refused — a developer machine and CI have no such variable, and the
 * failure mode of a missing secret must be a shut door rather than an open one.
 *
 * <p><strong>It returns before the ingestion finishes.</strong> Apps Script's {@code UrlFetchApp}
 * has its own timeout and the platform's wake can take seconds; a reconciliation over a long
 * window would outlast both. {@code 202 Accepted} says what actually happened: the work is
 * scheduled, and its outcome is on {@code ingestion_run} where every other run's is.
 */
@RestController
class SheetWakeController {

    private static final Logger log = LoggerFactory.getLogger(SheetWakeController.class);

    /** Also listed in {@code SecurityConfig}; a controller is invisible to the identity module. */
    private static final String PATH = "/api/ingestion/wake";

    /** A header, not a query parameter: query strings land in access logs and referrers. */
    private static final String SECRET_HEADER = "X-Wake-Secret";

    private final SheetsIngestionService sheets;
    private final TaskExecutor executor;

    /** {@code null} when no secret is configured, which is what closes the endpoint. */
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
     * Constant time, so the response cannot be timed to recover the secret a byte at a time.
     *
     * <p>{@link MessageDigest#isEqual} is the JDK's constant-time array comparison; {@code
     * String.equals} short-circuits on the first differing character, which is the leak.
     */
    private boolean authorised(String presented) {
        return secret != null
                && presented != null
                && MessageDigest.isEqual(secret, presented.getBytes(UTF_8));
    }
}
