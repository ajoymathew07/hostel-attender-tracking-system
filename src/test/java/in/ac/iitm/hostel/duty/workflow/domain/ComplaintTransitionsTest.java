package in.ac.iitm.hostel.duty.workflow.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The table itself, read as data (section 4.2).
 *
 * <p>No database and no Spring: these are claims about the table's shape, and the shape is what
 * every guarantee above it rests on. {@code ComplaintStateMachineIntegrationTest} then proves the
 * machine obeys it.
 */
class ComplaintTransitionsTest {

    @Test
    @DisplayName("every row of section 4.2 that moves an existing complaint is in the table")
    void theTableMatchesTheDesign() {
        // T1 is creation and has no source state (see WorkflowEvent), so 16 design rows remain,
        // plus the one marked addition: section 4.3's docket correction.
        assertThat(ComplaintTransitions.all()).hasSize(17);

        assertThat(ComplaintTransitions.all())
                .describedAs("every event in the vocabulary is reachable from somewhere")
                .extracting(ComplaintTransition::event)
                .containsAll(Set.of(WorkflowEvent.values()));
    }

    @Test
    @DisplayName("a pair the design does not name has no row, which is how the machine refuses")
    void absentPairsAreAbsent() {
        assertThat(ComplaintTransitions.matching(ComplaintStatus.NEW, WorkflowEvent.MARK_RESOLVED))
                .describedAs("resolving a complaint nobody has looked at is the whole failure mode")
                .isEmpty();
        assertThat(ComplaintTransitions.matching(ComplaintStatus.CLOSED, WorkflowEvent.MANAGER_REOPEN))
                .describedAs("CLOSED is terminal in section 4.2")
                .isEmpty();
        assertThat(ComplaintTransitions.matching(ComplaintStatus.NEW, WorkflowEvent.MANAGER_ASSIGN))
                .describedAs(
                        "auto_dispatch runs at ingest, so a manager never sees a NEW complaint and"
                                + " assigning one is always T4, out of TRIAGE")
                .isEmpty();
        assertThat(
                        ComplaintTransitions.matching(
                                ComplaintStatus.AWAITING_CMS, WorkflowEvent.MARK_RESOLVED))
                .describedAs("IN_PROGRESS is reachable only through T11, which is the docket gate")
                .isEmpty();
    }

    @Test
    @DisplayName("auto_dispatch is the one pair with two rows, split by its guard — T2 and T3")
    void autoDispatchForksOnItsGuard() {
        List<ComplaintTransition> rows =
                ComplaintTransitions.matching(ComplaintStatus.NEW, WorkflowEvent.AUTO_DISPATCH);

        assertThat(rows).hasSize(2);
        assertThat(rows)
                .extracting(ComplaintTransition::to)
                .containsExactly(ComplaintStatus.ASSIGNED, ComplaintStatus.TRIAGE);
        assertThat(rows)
                .extracting(ComplaintTransition::guard)
                .containsExactly(
                        TransitionGuard.DISPATCH_CANDIDATE, TransitionGuard.NO_DISPATCH_CANDIDATE);
    }

    @Test
    @DisplayName("all rows for one pair name one actor, so authorization never depends on the branch")
    void onePairMeansOneActor() {
        Map<String, List<ComplaintTransition>> byPair =
                ComplaintTransitions.all().stream()
                        .collect(Collectors.groupingBy(row -> row.from() + "/" + row.event()));

        assertThat(byPair.values())
                .allSatisfy(
                        rows ->
                                assertThat(rows)
                                        .extracting(ComplaintTransition::actor)
                                        .containsOnly(rows.get(0).actor()));
    }

    @Test
    @DisplayName("no row leaves a terminal state: CLOSED and REJECTED are the end of the diagram")
    void terminalStatesAreTerminal() {
        assertThat(ComplaintTransitions.all())
                .extracting(ComplaintTransition::from)
                .allSatisfy(from -> assertThat(from.isTerminal()).isFalse());
    }

    @Test
    @DisplayName("the docket gate: RESOLVED is reachable only from IN_PROGRESS, only by T14")
    void resolvedHasExactlyOneWayIn() {
        assertThat(ComplaintTransitions.all())
                .filteredOn(row -> row.to() == ComplaintStatus.RESOLVED)
                .singleElement()
                .satisfies(
                        row -> {
                            assertThat(row.from()).isEqualTo(ComplaintStatus.IN_PROGRESS);
                            assertThat(row.event()).isEqualTo(WorkflowEvent.MARK_RESOLVED);
                            assertThat(row.guard()).isEqualTo(TransitionGuard.DOCKET_FILED);
                        });
    }

    @Test
    @DisplayName("every guard carries the sentence the refusal will use — section 11.6")
    void guardsExplainThemselves() {
        assertThat(TransitionGuard.values())
                .extracting((Function<TransitionGuard, String>) TransitionGuard::reason)
                .allSatisfy(reason -> assertThat(reason).isNotBlank());
    }
}
