package in.ac.iitm.hostel.duty.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.ac.iitm.hostel.duty.audit.application.AuditEntry;
import in.ac.iitm.hostel.duty.audit.application.AuditTrail;
import in.ac.iitm.hostel.duty.audit.application.CorrelationId;
import in.ac.iitm.hostel.duty.audit.domain.AuditEvent;
import in.ac.iitm.hostel.duty.audit.infrastructure.AuditEventRepository;
import in.ac.iitm.hostel.duty.categorization.domain.CategoryTerm;
import in.ac.iitm.hostel.duty.categorization.infrastructure.CategoryTermRepository;
import in.ac.iitm.hostel.duty.identity.infrastructure.AppUserRepository;
import in.ac.iitm.hostel.duty.support.AbstractIntegrationTest;
import in.ac.iitm.hostel.duty.support.Fixtures;
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
 * The audit spine: the row lands in the <strong>same transaction</strong> as the change it records,
 * so the log can never disagree with the data (data model section 3.7).
 *
 * <p>Three properties, and the third is the one that actually distinguishes {@code BEFORE_COMMIT}
 * from {@code AFTER_COMMIT}:
 *
 * <ol>
 *   <li>a committed change has its audit row;
 *   <li>a rolled-back change leaves none;
 *   <li>a <em>rejected audit row takes the change down with it</em> — which could not happen if the
 *       audit write ran in a transaction of its own after the commit.
 * </ol>
 *
 * <p>{@code CategoryTerm} stands in for "some business change": it is the simplest entity in the
 * schema with no foreign keys, so the test is about the transaction and nothing else.
 */
@SpringBootTest
class AuditSpineIntegrationTest extends AbstractIntegrationTest {

    private static final String ENTITY_TYPE = "category_term";

    @Autowired private AuditTrail auditTrail;
    @Autowired private AuditEventRepository auditEvents;
    @Autowired private CategoryTermRepository terms;
    @Autowired private AppUserRepository users;
    @Autowired private TransactionTemplate tx;

    @Test
    @DisplayName("the audit row commits atomically with the change it records")
    void auditRowCommitsWithItsChange() {
        String term = Fixtures.unique("leaking");

        Long termId =
                tx.execute(
                        status -> {
                            CategoryTerm saved = terms.saveAndFlush(categoryTerm(term));
                            auditTrail.record(
                                    AuditEntry.change(
                                            ENTITY_TYPE,
                                            saved.getId(),
                                            "TERM_ADDED",
                                            "{\"term\":\"" + term + "\"}",
                                            null,
                                            "MANAGER"));
                            return saved.getId();
                        });

        assertThat(terms.findById(termId)).isPresent();
        List<AuditEvent> recorded =
                auditEvents.findByEntityTypeAndEntityIdOrderByOccurredAtAsc(ENTITY_TYPE, termId);
        assertThat(recorded).hasSize(1);
        assertThat(recorded.get(0).getEventType()).isEqualTo("TERM_ADDED");
        assertThat(recorded.get(0).getDetail()).contains(term);
    }

    @Test
    @DisplayName("a rolled-back change leaves no audit row behind")
    void rolledBackChangeLeavesNoAuditRow() {
        String term = Fixtures.unique("phantom");
        long auditRowsBefore = auditEvents.count();

        assertThatThrownBy(
                        () ->
                                tx.executeWithoutResult(
                                        status -> {
                                            CategoryTerm saved = terms.saveAndFlush(categoryTerm(term));
                                            auditTrail.record(
                                                    AuditEntry.change(
                                                            ENTITY_TYPE,
                                                            saved.getId(),
                                                            "TERM_ADDED",
                                                            null,
                                                            null,
                                                            "MANAGER"));
                                            throw new IllegalStateException("the change failed");
                                        }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(terms.findByDomainAndTerm("CIVIL", term))
                .describedAs("the change itself rolled back")
                .isEmpty();
        assertThat(auditEvents.count())
                .describedAs("and so did its audit row — the log records nothing that did not happen")
                .isEqualTo(auditRowsBefore);
    }

    @Test
    @DisplayName("a rejected audit row rolls the change back with it — proof the write shares the transaction")
    void aFailedAuditWriteRollsBackTheChange() {
        String term = Fixtures.unique("doomed");
        // event_type is VARCHAR(48); this one cannot be stored, so the audit insert fails at flush
        // inside BEFORE_COMMIT, while the business transaction is still open.
        String tooLongForTheColumn = "X".repeat(64);

        assertThatThrownBy(
                        () ->
                                tx.executeWithoutResult(
                                        status -> {
                                            CategoryTerm saved = terms.saveAndFlush(categoryTerm(term));
                                            auditTrail.record(
                                                    AuditEntry.change(
                                                            ENTITY_TYPE,
                                                            saved.getId(),
                                                            tooLongForTheColumn,
                                                            null,
                                                            null,
                                                            "MANAGER"));
                                        }))
                .describedAs("the failing audit insert must surface, not be swallowed")
                .isInstanceOf(Exception.class);

        assertThat(terms.findByDomainAndTerm("CIVIL", term))
                .describedAs(
                        "under AFTER_COMMIT this row would have survived; under BEFORE_COMMIT the"
                                + " change cannot commit without its audit row")
                .isEmpty();
    }

    @Test
    @DisplayName("correlation_id ties every row written by one request or ingestion run together")
    void correlationIdGroupsOneUnitOfWork() {
        String correlationId = CorrelationId.generate();
        String first = Fixtures.unique("seepage");
        String second = Fixtures.unique("cracked");

        CorrelationId.runWith(
                correlationId,
                () ->
                        tx.executeWithoutResult(
                                status -> {
                                    CategoryTerm a = terms.saveAndFlush(categoryTerm(first));
                                    CategoryTerm b = terms.saveAndFlush(categoryTerm(second));
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
        String term = Fixtures.unique("transition");
        Long managerId = users.saveAndFlush(Fixtures.manager()).getId();

        Long termId =
                tx.execute(
                        status -> {
                            CategoryTerm saved = terms.saveAndFlush(categoryTerm(term));
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
                        .findByEntityTypeAndEntityIdOrderByOccurredAtAsc(ENTITY_TYPE, termId)
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
                                                                "TERM_ADDED",
                                                                null,
                                                                999_999_999L,
                                                                "MANAGER"))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private CategoryTerm categoryTerm(String term) {
        return new CategoryTerm(
                "CIVIL", term, new BigDecimal("1.00"), Instant.parse("2026-02-01T00:00:00Z"));
    }
}
