package duty.sim;

import static java.nio.charset.StandardCharsets.UTF_8;

import duty.analytics.application.AnalyticsService;
import duty.identity.application.CurrentUser.SignedInUser;
import duty.identity.application.UserProvisioningService;
import duty.identity.domain.AppUser.UserRole;
import duty.ingestion.application.CsvUploadService;
import duty.ingestion.application.CsvUploadService.UploadedFile;
import duty.ingestion.application.IngestionPipeline.IngestionSummary;
import duty.workflow.application.AttenderQueueService;
import duty.workflow.application.AttenderQueueService.QueuedTask;
import duty.workflow.application.PriorityService;
import duty.workflow.application.TaskResolutionService;
import duty.workflow.application.WorkflowService;
import duty.workflow.application.WorkflowService.TransitionPayload;
import duty.workflow.domain.Complaint.ComplaintStatus;
import duty.workflow.domain.ComplaintTransitions.WorkflowEvent;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongConsumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

/**
 * A load simulation: one manager, four attenders, and complaints that arrive and are worked at random
 * times. It is not part of the test suite. It runs only when {@code -Dsim} is given, against whatever
 * database {@code DATABASE_URL} names, and it serves the app on port 8080 while it runs, so the
 * manager can sign in and watch.
 *
 * <pre>
 * DATABASE_URL=... DATABASE_USERNAME=... DATABASE_PASSWORD=... \
 * GOOGLE_CLIENT_ID=... GOOGLE_CLIENT_SECRET=... \
 * caffeinate -i ./mvnw test -Dtest=LoadSimulation -Dsim=history,live,burst -Dsim.hold=60
 * </pre>
 *
 * <p>Against Neon, history takes about half an hour (a second or so per step). {@code caffeinate} keeps
 * a Mac awake for it: a sleeping laptop freezes the run, and its connections time out on waking.
 *
 * <ul>
 *   <li>{@code history} writes {@code sim.days} (56) days of past complaints, with one case planted
 *       for each of the detectors A1 to A4, then runs the checks.
 *   <li>{@code live} runs in real time for {@code sim.minutes} (30): a complaint every two minutes or
 *       so, and one working hour of handling passes every {@code sim.hourSeconds} (20) seconds.
 *   <li>{@code burst} ingests {@code sim.burst} (200) complaints at once and has all four attenders
 *       work them in parallel, then prints the response times.
 *   <li>{@code sim.hold} keeps the app up for that many minutes afterwards, for screenshots.
 * </ul>
 *
 * <p>Every step goes through the real services as the manager or the attender who would take it, so
 * the state machine, audit trail, points ledger and live dashboard all behave as in production. The
 * app reads the system clock, so a step taken now cannot be dated in the past. For history, each
 * step's rows are moved back to the simulated time straight after the step: every timestamp the step
 * wrote lies between the moments just before and after it, and nothing else does.
 */
@SpringBootTest(
        webEnvironment = WebEnvironment.DEFINED_PORT,
        properties = {
            "server.port=${sim.port:8080}",
            "logging.level.duty=INFO",
            // The ageing sweep reads the real clock, so while history is being written it would find
            // every complaint overdue. The simulation runs the sweep itself once history is in place.
            "workflow.sla.ageing-interval=PT24H"
        })
@ActiveProfiles("local")
@EnabledIfSystemProperty(named = "sim", matches = ".+")
class LoadSimulation {

    private static final Logger log = LoggerFactory.getLogger(LoadSimulation.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter FORM_TIME = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");
    private static final DateTimeFormatter SQL_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS").withZone(ZoneOffset.UTC);
    private static final String HEADER =
            "Timestamp,Email Address,Name,Roll Number,Phone Number,Room Number,Describe the issue,Type of issue\n";

    /** Median hours to pick up, inspect, get a docket, and repair. Civil waits longest for its docket. */
    private static final Map<String, double[]> MEDIAN_HOURS =
            Map.of(
                    "Electrical", new double[] {0.7, 0.5, 1.5, 3},
                    "Civil", new double[] {2, 1, 16, 10},
                    "RO", new double[] {1, 0.7, 2, 4},
                    "LAN", new double[] {1, 0.5, 1, 3});

    private static final Map<String, List<String>> TEXTS =
            Map.of(
                    "Electrical", List.of("Tube light not working", "Fan regulator broken", "Socket near the bed gives a spark"),
                    "Civil", List.of("Bathroom tap leaking", "Door hinge broken", "Window glass cracked"),
                    "RO", List.of("RO filter not working on this floor", "No water from the RO cooler"),
                    "LAN", List.of("LAN port dead", "Internet very slow in the room"),
                    "Others", List.of("Mess food was cold", "Need an extra mattress"));

    @Autowired private CsvUploadService uploads;
    @Autowired private TaskResolutionService tasks;
    @Autowired private AttenderQueueService queue;
    @Autowired private WorkflowService workflow;
    @Autowired private PriorityService priorities;
    @Autowired private UserProvisioningService users;
    @Autowired private AnalyticsService analytics;
    @Autowired private JdbcTemplate jdbc;

    private final Random random = new Random(Long.getLong("sim.seed", 7));
    private final PriorityQueue<Step> steps = new PriorityQueue<>(Comparator.comparing(Step::at));
    private final List<SignedInUser> attenders = new ArrayList<>();
    /** How slowly each attender works: 1 is typical, 1.8 is the slow one. */
    private final Map<Long, Double> pace = new HashMap<>();
    private SignedInUser manager;
    private List<String[]> timestampColumns;
    private Duration hour;
    private Instant end;
    private int rows;
    private int failures;

    private record Step(Instant at, Runnable action) {}

    @Test
    void run() throws InterruptedException {
        Set<String> phases = Set.of(System.getProperty("sim").split(","));
        setUp();
        if (phases.contains("history")) {
            history(Integer.getInteger("sim.days", 56));
        }
        if (phases.contains("live")) {
            live(Duration.ofMinutes(Integer.getInteger("sim.minutes", 30)));
        }
        if (phases.contains("burst")) {
            burst(Integer.getInteger("sim.burst", 200));
        }
        report();
        log.info("Holding the app at http://localhost:8080 for {} minute(s)", Integer.getInteger("sim.hold", 0));
        Thread.sleep(Duration.ofMinutes(Integer.getInteger("sim.hold", 0)));
    }

    /** The real manager, four attenders (created once), and which floors each covers. */
    private void setUp() {
        manager =
                jdbc.query(
                                "SELECT id, email, display_name FROM app_user WHERE role = 'MANAGER' AND active"
                                        + " AND email NOT LIKE '%.invalid' ORDER BY id LIMIT 1",
                                (row, n) -> new SignedInUser(row.getLong(1), row.getString(2), row.getString(3), UserRole.MANAGER))
                        .stream()
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("Replace a placeholder manager first (deploy.md step 6)"));
        as(manager);
        String[] names = {"Ravi", "Meena", "Arun", "Divya"};
        double[] paces = {1.0, 0.8, 1.2, 1.8};
        for (int i = 0; i < names.length; i++) {
            String email = "sim.attender" + (i + 1) + "@example.com";
            String name = names[i] + " (sim)";
            long id =
                    jdbc.query("SELECT id FROM app_user WHERE email = ?", (row, n) -> row.getLong(1), email).stream()
                            .findFirst()
                            .orElseGet(() -> users.provision(name, email, UserRole.ATTENDER).id());
            // The first three each own a floor; the fourth helps on all of them.
            int floor = i < 3 ? i + 1 : 0;
            jdbc.update(
                    "INSERT INTO floor_assignment (attender_id, floor_id, coverage) SELECT ?, id, ? FROM floor"
                            + " WHERE ? = 0 OR floor_number = ? ON CONFLICT ON CONSTRAINT uq_fa DO NOTHING",
                    id,
                    floor == 0 ? "SECONDARY" : "PRIMARY",
                    floor,
                    floor);
            attenders.add(new SignedInUser(id, email, name, UserRole.ATTENDER));
            pace.put(id, paces[i]);
        }
        timestampColumns =
                jdbc.query(
                        """
                        SELECT table_name, column_name FROM information_schema.columns
                        WHERE table_schema = 'public' AND data_type = 'timestamp without time zone'
                          AND table_name NOT IN ('shedlock', 'flyway_schema_history', 'analytics_alert',
                                                 'app_user', 'app_setting', 'push_subscription')
                        """,
                        (row, n) -> new String[] {row.getString(1), row.getString(2)});
    }

    /** Weeks of past complaints, with one case planted for each detector, then the checks. */
    private void history(int days) {
        hour = Duration.ofHours(1);
        end = Instant.now();
        LocalDate today = LocalDate.now(IST);
        for (LocalDate day = today.minusDays(days); day.isBefore(today); day = day.plusDays(1)) {
            int count = poisson(day.getDayOfWeek().getValue() >= 6 ? 3 : 4.5);
            if (day.equals(today.minusDays(1))) {
                count += 14; // A3: a surge yesterday
            }
            for (int k = 0; k < count; k++) {
                // Most complaints come in the evening, when residents are back in their rooms.
                int hourOfDay = random.nextDouble() < 0.7 ? 17 + random.nextInt(7) : 8 + random.nextInt(9);
                Instant when = day.atTime(hourOfDay, random.nextInt(60)).atZone(IST).toInstant();
                String domain = domain();
                boolean outage = domain.equals("LAN") && day.isAfter(today.minusDays(8));
                // A4: a network outage over the last week makes LAN repairs take days.
                complaint(when, domain, room(), text(domain), outage ? new double[] {1, 0.5, 1, 40} : MEDIAN_HOURS.get(domain));
            }
        }
        // A2: the same Civil fault three times from one room.
        for (int ago : new int[] {12, 7, 3}) {
            complaint(evening(today.minusDays(ago)), "Civil", "214", "Bathroom tap leaking again", MEDIAN_HOURS.get("Civil"));
        }
        // A1: one Electrical repair that waits ten days for a part.
        complaint(evening(today.minusDays(25)), "Electrical", "131", "Geyser not heating", new double[] {0.7, 0.5, 1.5, 240});

        log.info("History: {} complaint(s) over {} days", steps.size(), days);
        drain(true);

        // The ageing sweep sets the breach flag, but it reads the real clock. For history it is decided
        // here by the sweep's own rule (the due date passed while the work was still open); open
        // complaints are left to the sweep itself, which runs straight after at the real "now".
        jdbc.update("UPDATE complaint SET sla_breached = true WHERE sla_due_at < COALESCE(resolved_at, closed_at)");
        // A point also carries its India-time business date, a DATE column that moving timestamps
        // does not touch. The app derives it from the moment of the credit, so derive it the same way.
        jdbc.update(
                """
                UPDATE point_ledger
                SET effective_date = CAST(created_at AT TIME ZONE 'UTC' AT TIME ZONE 'Asia/Kolkata' AS date),
                    period_key = to_char(created_at AT TIME ZONE 'UTC' AT TIME ZONE 'Asia/Kolkata', 'YYYY-MM')
                WHERE reason_code IN ('COMPLAINT_RESOLVED', 'REVERSAL')
                """);
        as(manager);
        log.info("Ageing escalated {} open complaint(s)", priorities.ageOpenComplaints());
        log.info("Analytics raised or refreshed {} alert(s)", analytics.runChecksNow());
    }

    /** Real time: complaints keep arriving and attenders work them, compressed so it is watchable. */
    private void live(Duration length) {
        hour = Duration.ofSeconds(Integer.getInteger("sim.hourSeconds", 20));
        Instant start = Instant.now();
        end = start.plus(length);
        for (Instant at = start; at.isBefore(end); at = at.plusSeconds((long) (-120 * Math.log(1 - random.nextDouble())))) {
            String domain = domain();
            complaint(at, domain, room(), text(domain), MEDIAN_HOURS.get(domain));
        }
        log.info("Live: {} complaint(s) over {}", steps.size(), length);
        drain(false);
    }

    /** Many complaints at once, all four attenders working in parallel; prints the response times. */
    private void burst(int count) throws InterruptedException {
        as(manager);
        StringBuilder csv = new StringBuilder(HEADER);
        Instant start = Instant.now();
        for (int k = 0; k < count; k++) {
            String domain = List.of("Electrical", "Civil", "RO", "LAN").get(random.nextInt(4));
            csv.append(row(start.minusSeconds(k), domain, room(), text(domain)));
        }
        long began = System.nanoTime();
        IngestionSummary summary = uploads.upload(new UploadedFile("burst.csv", "text/csv", csv.toString().getBytes(UTF_8)));
        log.info("Burst: ingested {} complaint(s) in {} ms", summary.rowsCreated(), (System.nanoTime() - began) / 1_000_000);

        List<Long> millis = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger errors = new AtomicInteger();
        List<LongConsumer> journey =
                List.of(
                        tasks::startInspection,
                        tasks::confirmDefect,
                        taskId -> tasks.recordDocket(taskId, "TT%06d".formatted(taskId % 1_000_000)),
                        tasks::resolve);
        ExecutorService pool = Executors.newFixedThreadPool(attenders.size());
        for (SignedInUser attender : attenders) {
            pool.submit(
                    () -> {
                        as(attender);
                        for (QueuedTask task : queue.myQueue()) {
                            if (task.status() != ComplaintStatus.ASSIGNED || task.assignedAt().isBefore(start)) {
                                continue;
                            }
                            for (LongConsumer step : journey) {
                                long stepBegan = System.nanoTime();
                                try {
                                    step.accept(task.taskId());
                                } catch (RuntimeException e) {
                                    errors.incrementAndGet();
                                    log.warn("Burst step failed for {}: {}", task.publicRef(), e.toString());
                                    break;
                                }
                                millis.add((System.nanoTime() - stepBegan) / 1_000_000);
                            }
                        }
                    });
        }
        pool.shutdown();
        pool.awaitTermination(1, TimeUnit.HOURS);
        Collections.sort(millis);
        if (!millis.isEmpty()) {
            log.info(
                    "Burst: {} attender step(s), {} error(s); p50 {} ms, p95 {} ms, max {} ms",
                    millis.size(),
                    errors.get(),
                    millis.get(millis.size() / 2),
                    millis.get(millis.size() * 95 / 100),
                    millis.get(millis.size() - 1));
        }
    }

    /** Where things stand: complaints by status, and the open alerts. */
    private void report() {
        as(manager);
        jdbc.query(
                "SELECT status, count(*) FROM complaint GROUP BY status ORDER BY status",
                row -> {
                    log.info("{}: {}", row.getString(1), row.getLong(2));
                });
        analytics.openAlerts().forEach(alert -> log.info("Alert {} {}: {}", alert.detector(), alert.severity(), alert.summary()));
        log.info("{} step(s) failed", failures);
    }

    /** A resident files a complaint; schedules what happens to it afterwards. */
    private void complaint(Instant at, String domain, String room, String text, double[] medianHours) {
        schedule(
                at,
                () -> {
                    as(manager);
                    long complaintId = ingest(at, domain, room, text);
                    String status = jdbc.queryForObject("SELECT status FROM complaint WHERE id = ?", String.class, complaintId);
                    if (status.equals(ComplaintStatus.TRIAGE.name())) {
                        schedule(
                                at.plus(hours(1 + random.nextDouble() * 5)),
                                () -> {
                                    as(manager);
                                    workflow.transition(
                                            complaintId, WorkflowEvent.MANAGER_REJECT, TransitionPayload.reason("Not a maintenance issue"));
                                });
                    } else {
                        work(complaintId, at, medianHours);
                    }
                });
    }

    /** The holder's steps, at random intervals scaled by how fast they work, then the manager's sign-off. */
    private void work(long complaintId, Instant from, double[] medianHours) {
        long taskId = jdbc.queryForObject("SELECT id FROM task WHERE complaint_id = ?", Long.class, complaintId);
        long holderId =
                jdbc.queryForObject(
                        "SELECT attender_id FROM task_assignment WHERE task_id = ? AND status = 'ACTIVE'", Long.class, taskId);
        SignedInUser holder = attenders.stream().filter(a -> a.getUserId() == holderId).findFirst().orElseThrow();
        double slowness = pace.get(holderId);

        Instant started = from.plus(hours(spread(medianHours[0]) * slowness));
        Instant inspected = started.plus(hours(spread(medianHours[1]) * slowness));
        // The docket wait is the Engineering Unit's, so the attender's pace does not change it. About a
        // quarter of repairs need no docket and start straight away.
        boolean needsDocket = random.nextDouble() < 0.75;
        Instant docketed = inspected.plus(hours(needsDocket ? spread(medianHours[2]) : 0.2));
        Instant resolved = docketed.plus(hours(spread(medianHours[3]) * slowness));
        Instant closed = resolved.plus(hours(6 + random.nextDouble() * 42));

        schedule(started, () -> attender(holder, () -> tasks.startInspection(taskId)));
        if (random.nextDouble() < 0.1) {
            schedule(inspected, () -> attender(holder, () -> tasks.reportNoDefect(taskId, "Checked on site; working normally")));
            return;
        }
        schedule(inspected, () -> attender(holder, () -> tasks.confirmDefect(taskId)));
        String docket = needsDocket ? "TT%06d".formatted(complaintId % 1_000_000) : "";
        schedule(docketed, () -> attender(holder, () -> tasks.recordDocket(taskId, docket)));
        schedule(resolved, () -> attender(holder, () -> tasks.resolve(taskId)));
        schedule(
                closed,
                () -> {
                    as(manager);
                    workflow.transition(complaintId, WorkflowEvent.MANAGER_CLOSE, TransitionPayload.none());
                });
    }

    private void attender(SignedInUser who, Runnable step) {
        as(who);
        step.run();
    }

    /** Runs the steps in time order. For history, moves each step's rows back to its simulated time. */
    private void drain(boolean backdate) {
        int done = 0;
        while (!steps.isEmpty()) {
            Step step = steps.poll();
            if (!backdate) {
                sleepUntil(step.at());
            }
            Instant before = Instant.now().minusMillis(1);
            try {
                step.action().run();
            } catch (RuntimeException e) {
                failures++;
                log.warn("Step failed: {}", e.toString());
            }
            if (backdate) {
                moveBack(before, Instant.now().plusMillis(1), Duration.between(step.at(), before));
            }
            if (++done % 200 == 0) {
                log.info("{} step(s) done, {} to go", done, steps.size());
            }
        }
    }

    /** Moves every timestamp written in {@code [from, to]} back by {@code by}, in one round trip. */
    private void moveBack(Instant from, Instant to, Duration by) {
        jdbc.batchUpdate(
                timestampColumns.stream()
                        .map(
                                column ->
                                        "UPDATE %1$s SET %2$s = %2$s - interval '%3$d milliseconds' WHERE %2$s BETWEEN '%4$s' AND '%5$s'"
                                                .formatted(column[0], column[1], by.toMillis(), SQL_TIME.format(from), SQL_TIME.format(to)))
                        .toArray(String[]::new));
    }

    private void schedule(Instant at, Runnable action) {
        if (at.isBefore(end)) {
            steps.add(new Step(at, action));
        }
    }

    /** Uploads one form response, exactly as the CSV fallback would, and returns the complaint it made. */
    private long ingest(Instant reportedAt, String domain, String room, String text) {
        IngestionSummary summary =
                uploads.upload(new UploadedFile("sim.csv", "text/csv", (HEADER + row(reportedAt, domain, room, text)).getBytes(UTF_8)));
        return jdbc.queryForObject("SELECT complaint_id FROM source_row WHERE first_seen_run = ?", Long.class, summary.runId());
    }

    private String row(Instant reportedAt, String domain, String room, String text) {
        int n = ++rows;
        return "%s,sim.resident%d@example.com,Resident %d,CS23B%03d,9000%06d,%s,%s,%s\n"
                .formatted(FORM_TIME.format(reportedAt.atZone(IST)), n, n, n % 1000, n, room, text, domain);
    }

    private static void as(SignedInUser user) {
        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken(user, null, "ROLE_" + user.getRole().name()));
    }

    private static void sleepUntil(Instant at) {
        long wait = Duration.between(Instant.now(), at).toMillis();
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static Instant evening(LocalDate day) {
        return day.atTime(19, 30).atZone(IST).toInstant();
    }

    /** One simulated working hour, which in the live phase passes in seconds. */
    private Duration hours(double count) {
        return Duration.ofMillis((long) (count * hour.toMillis()));
    }

    /** A duration around {@code median}: log-normal, so most are near it and a few take much longer. */
    private double spread(double median) {
        return median * Math.exp(0.45 * random.nextGaussian());
    }

    private String domain() {
        double r = random.nextDouble();
        return r < 0.30 ? "Electrical" : r < 0.60 ? "Civil" : r < 0.80 ? "LAN" : r < 0.94 ? "RO" : "Others";
    }

    private String text(String domain) {
        List<String> texts = TEXTS.get(domain);
        return texts.get(random.nextInt(texts.size()));
    }

    /** A Cauvery room: the first digit is the floor (1 to 3). */
    private String room() {
        return String.valueOf((1 + random.nextInt(3)) * 100 + random.nextInt(100));
    }

    private int poisson(double mean) {
        int count = 0;
        for (double p = Math.exp(-mean), product = random.nextDouble(); product > p; product *= random.nextDouble()) {
            count++;
        }
        return count;
    }
}
