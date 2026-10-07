package duty.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import duty.audit.application.AuditTrail.AuditEntry;
import duty.audit.application.AuditTrail.CorrelationId;
import duty.audit.application.AuditTrail;
import duty.audit.domain.AuditEvent;
import duty.audit.infrastructure.AuditEventRepository;
import duty.dispatch.domain.Floor;
import duty.dispatch.infrastructure.FloorRepository;
import duty.identity.infrastructure.AppUserRepository;
import duty.support.AbstractIntegrationTest;
import duty.support.Fixtures;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The audit row is written in the same transaction as the change: a committed change has its row, a
 * rolled-back change has none, and a rejected audit row rolls the change back too.
 *
 * <p>{@code Floor} is used as the business change because it is the simplest entity.
 */
@SpringBootTest
class AuditSpineIntegrationTest extends AbstractIntegrationTest {

    private static final String ENTITY_TYPE = "floor";

    @Autowired private AuditTrail auditTrail;
    @Autowired private AuditEventRepository auditEvents;
    @Autowired private FloorRepository floors;
    @Autowired private AppUserRepository users;
    @Autowired private TransactionTemplate tx;

    @Test
    @DisplayName("the audit row commits atomically with the change it records")
    void auditRowCommitsWithItsChange() {
        String wing = Fixtures.unique("W");

        Long floorId =
                tx.execute(
                        status -> {
                            Floor saved = floors.saveAndFlush(floor(wing));
                            auditTrail.record(
                                    AuditEntry.change(
                                            ENTITY_TYPE,
                                            saved.getId(),
                                            "FLOOR_ADDED",
                                            "{\"wing\":\"" + wing + "\"}",
                                            null,
                                            "MANAGER"));
                            return saved.getId();
                        });

        assertThat(floors.findById(floorId)).isPresent();
        List<AuditEvent> recorded =
                auditEvents.findByEntityTypeAndEntityIdOrderByOccurredAtAsc(ENTITY_TYPE, floorId);
        assertThat(recorded).hasSize(1);
        assertThat(recorded.get(0).getEventType()).isEqualTo("FLOOR_ADDED");
        assertThat(recorded.get(0).getDetail()).contains(wing);
    }

    @Test
    @DisplayName("a rolled-back change leaves no audit row behind")
    void rolledBackChangeLeavesNoAuditRow() {
        String wing = Fixtures.unique("W");
        long auditRowsBefore = auditEvents.count();

        assertThatThrownBy(
                        () ->
                                tx.executeWithoutResult(
                                        status -> {
                                            Floor saved = floors.saveAndFlush(floor(wing));
                                            auditTrail.record(
                                                    AuditEntry.change(
                                                            ENTITY_TYPE,
                                                            saved.getId(),
                                                            "FLOOR_ADDED",
                                                            null,
                                                            null,
                                                            "MANAGER"));
                                            throw new IllegalStateException("the change failed");
                                        }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(floors.findByWingAndFloorNumber(wing, 1))
                .describedAs("the change itself rolled back")
                .isEmpty();
        assertThat(auditEvents.count())
                .describedAs("and so did its audit row — the log records nothing that did not happen")
                .isEqualTo(auditRowsBefore);
    }

    @Test
    @DisplayName("a rejected audit row rolls the change back with it — proof the write shares the transaction")
    void aFailedAuditWriteRollsBackTheChange() {
        String wing = Fixtures.unique("W");
        // event_type is VARCHAR(48); this one cannot be stored, so the audit insert fails at flush
        // inside BEFORE_COMMIT, while the business transaction is still open.
        String tooLongForTheColumn = "X".repeat(64);

        assertThatThrownBy(
                        () ->
                                tx.executeWithoutResult(
                                        status -> {
                                            Floor saved = floors.saveAndFlush(floor(wing));
                                            auditTrail.record(
                                                    AuditEntry.change(
                                                            ENTITY_TYPE,
                                                            saved.getId(),
                                                            tooLongForTheColumn,
                                                            null,
                                                            null,
                                                            "MANAGER"));
                                        }))
                .describedAs(
                        "the failing audit insert must surface as the database rejection it is, not"
                                + " be swallowed and not be some unrelated failure that would let"
                                + " this test pass for the wrong reason")
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(floors.findByWingAndFloorNumber(wing, 1))
                .describedAs(
                        "under AFTER_COMMIT this row would have survived; under BEFORE_COMMIT the"
                                + " change cannot commit without its audit row")
                .isEmpty();
    }

    @Test
    @DisplayName("correlation_id ties every row written by one request or ingestion run together")
    void correlationIdGroupsOneUnitOfWork() {
        String correlationId = CorrelationId.generate();
        String first = Fixtures.unique("W");
        String second = Fixtures.unique("W");

        CorrelationId.runWith(
                correlationId,
                () ->
                        tx.executeWithoutResult(
                                status -> {
                                    Floor a = floors.saveAndFlush(floor(first));
                                    Floor b = floors.saveAndFlush(floor(second));
                                    auditTrail.record(
                                            AuditEntry.systemAction(
                                                    ENTITY_TYPE, a.getId(), "TERM_IMPORTED", null));
                                    auditTrail.record(
                                            AuditEntry.systemAction(
                                                    ENTITY_TYPE, b.getId(), "TERM_IMPORTED", null));
                                }));

        List<AuditEvent> forRun = auditEvents.findByCorrelationIdOrderByOccurredAtAsc(correlationId);
        assertThat(forRun).hasSize(2);
        assertThat(forRun).allSatisfy(event -> assertThat(event.isSystemAction()).isTrue());
        assertThat(CorrelationId.current())
                .describedAs("the thread must not keep the correlation afterwards")
                .isNull();
    }

    @Test
    @DisplayName("an entry recorded outside a transaction is dropped, never written standalone")
    void entryOutsideATransactionIsDropped() {
        long before = auditEvents.count();

        auditTrail.record(AuditEntry.systemAction(ENTITY_TYPE, 1L, "NO_TRANSACTION", null));

        assertThat(auditEvents.count())
                .describedAs(
                        "a standalone audit row would be a claim about a change that may never have"
                                + " happened")
                .isEqualTo(before);
    }

    @Test
    @DisplayName("a state transition records both ends of the move, and its actor")
    void transitionRecordsFromAndToState() {
        String wing = Fixtures.unique("W");
        Long managerId = users.saveAndFlush(Fixtures.manager()).getId();

        Long floorId =
                tx.execute(
                        status -> {
                            Floor saved = floors.saveAndFlush(floor(wing));
                            auditTrail.record(
                                    AuditEntry.transition(
                                            ENTITY_TYPE,
                                            saved.getId(),
                                            "TERM_RETIRED",
                                            "ACTIVE",
                                            "RETIRED",
                                            managerId,
                                            "MANAGER"));
                            return saved.getId();
                        });

        AuditEvent event =
                auditEvents
                        .findByEntityTypeAndEntityIdOrderByOccurredAtAsc(ENTITY_TYPE, floorId)
                        .get(0);
        assertThat(event.isStateTransition()).isTrue();
        assertThat(event.getFromState()).isEqualTo("ACTIVE");
        assertThat(event.getToState()).isEqualTo("RETIRED");
        assertThat(event.getActorId()).isEqualTo(managerId);
        assertThat(event.getActorRole()).isEqualTo("MANAGER");
        assertThat(event.isSystemAction()).isFalse();
        assertThat(event.getOccurredAt()).isNotNull();
    }

    @Test
    @DisplayName("actor_id is a real foreign key, while (entity_type, entity_id) deliberately is not")
    void actorIsAForeignKeyButTheSubjectIsNot() {
        // The subject may be any aggregate in any table, so no FK can exist for it: an id that
        // matches nothing is accepted.
        Long orphanSubject = 999_999_999L;
        tx.executeWithoutResult(
                status ->
                        auditTrail.record(
                                AuditEntry.systemAction("complaint", orphanSubject, "PURGED", null)));
        assertThat(auditEvents.findByEntityTypeAndEntityIdOrderByOccurredAtAsc("complaint", orphanSubject))
                .hasSize(1);

        // An actor, by contrast, is always an app_user.
        assertThatThrownBy(
                        () ->
                                tx.executeWithoutResult(
                                        status ->
                                                auditTrail.record(
                                                        AuditEntry.change(
                                                                ENTITY_TYPE,
                                                                1L,
                                                                "FLOOR_ADDED",
                                                                null,
                                                                999_999_999L,
                                                                "MANAGER"))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** A wing nobody else uses, so floors from other test classes cannot collide. */
    private Floor floor(String wing) {
        return new Floor(wing, 1, "Ground");
    }
}
