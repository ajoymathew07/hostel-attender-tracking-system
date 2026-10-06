package duty.dispatch.application;

import duty.audit.application.AuditDetail;
import duty.dispatch.domain.Coverage;
import duty.dispatch.domain.DispatchCandidate;
import duty.dispatch.domain.DispatchDecision;
import duty.dispatch.domain.DispatchPolicy;
import duty.dispatch.domain.FloorAssignment;
import duty.dispatch.infrastructure.AvailabilityWindowRepository;
import duty.dispatch.infrastructure.FloorAssignmentRepository;
import duty.dispatch.infrastructure.FloorRepository;
import duty.identity.application.UserProvisioningService;
import duty.workflow.application.AttenderWorkload;
import duty.workflow.application.AttenderWorkloadService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <strong>Stage 1 of section 6.2 — the hard eligibility filter — and the call into the
 * heuristic.</strong>
 *
 * <p>Availability is a gate and not a score: an attender on leave is removed here, before anything
 * is weighed, because folding it into the weighted sum would let a strong floor match outvote the
 * fact that somebody is not at work.
 *
 * <p>The scoring itself is {@link DispatchPolicy}, which has no database and no Spring in it. This
 * class gathers the inputs, and nothing else.
 */
@Service
@Transactional(readOnly = true)
public class AttenderDispatcher {

    private final UserProvisioningService attenders;
    private final AvailabilityWindowRepository availability;
    private final FloorAssignmentRepository coverage;
    private final FloorRepository floors;
    private final AttenderWorkloadService workloads;
    private final DispatchSettings settings;

    AttenderDispatcher(
            UserProvisioningService attenders,
            AvailabilityWindowRepository availability,
            FloorAssignmentRepository coverage,
            FloorRepository floors,
            AttenderWorkloadService workloads,
            DispatchSettings settings) {
        this.attenders = attenders;
        this.availability = availability;
        this.coverage = coverage;
        this.floors = floors;
        this.workloads = workloads;
        this.settings = settings;
    }

    /**
     * Who should do the work on this floor.
     *
     * <p>Empty is {@code E = ∅} — everyone is on leave, off duty, or already holds this complaint
     * — and the caller sends the complaint to triage. There is no third outcome, which is what
     * makes R1 hold: dispatch either names somebody or says why it could not.
     *
     * @param floorId the complaint's floor; the caller has already refused a complaint without one
     * @param alreadyAssigned attenders who hold this complaint's task, who cannot be given it again
     */
    public Optional<DispatchResult> choose(long floorId, Set<Long> alreadyAssigned) {
        Instant now = Instant.now();
        // Read once per dispatch, so every candidate is scored by the same weights even if the
        // manager presses Save halfway through a poll.
        DispatchPolicy policy = settings.policy();
        int floorNumber =
                floors.findById(floorId).orElseThrow().getFloorNumber();
        Map<Long, List<FloorAssignment>> covered = coverageByAttender();

        List<DispatchCandidate> eligible = new ArrayList<>();
        for (Long attenderId : attenders.activeAttenderIds()) {
            if (alreadyAssigned.contains(attenderId) || !isAvailable(attenderId, now)) {
                continue;
            }
            eligible.add(
                    candidate(attenderId, covered.getOrDefault(attenderId, List.of()), floorId, floorNumber, now));
        }

        return policy.decide(eligible).map(decision -> toResult(decision, policy));
    }

    /**
     * {@code availabilityAt(a, now) = AVAILABLE}.
     *
     * <p>Silence means available. Attenders declare leave and off-duty spells; nobody records
     * "I am at work today", and treating an absent window as absence would empty the candidate
     * set on the first day of use.
     */
    private boolean isAvailable(long attenderId, Instant now) {
        return availability.findInForce(attenderId, now).stream()
                .findFirst()
                .map(window -> window.getStatus().eligibleForDispatch())
                .orElse(true);
    }

    /** One attender's floor terms: their coverage of this floor, or how far away they work. */
    private DispatchCandidate candidate(
            long attenderId,
            List<FloorAssignment> theirFloors,
            long floorId,
            int floorNumber,
            Instant now) {
        Coverage onThisFloor =
                theirFloors.stream()
                        .filter(assignment -> assignment.getFloor().getId().equals(floorId))
                        .map(FloorAssignment::getCoverage)
                        .findFirst()
                        .orElse(null);
        OptionalInt nearest =
                theirFloors.stream()
                        .mapToInt(
                                assignment -> Math.abs(assignment.getFloor().getFloorNumber() - floorNumber))
                        .min();
        // Null means they cover no floor at all, which the proximity table scores at its minimum
        // rather than at zero — somebody still has to go (section 6.2).
        Integer distance = null;
        if (onThisFloor != null) {
            distance = 0;
        } else if (nearest.isPresent()) {
            distance = nearest.getAsInt();
        }

        AttenderWorkload workload = workloads.of(attenderId, now);
        return new DispatchCandidate(
                attenderId,
                onThisFloor,
                distance,
                workload.weighted(),
                workload.assignedToday(),
                workload.lastAssignedAt());
    }

    private Map<Long, List<FloorAssignment>> coverageByAttender() {
        Map<Long, List<FloorAssignment>> byAttender = new LinkedHashMap<>();
        for (FloorAssignment assignment : coverage.findAllWithFloor()) {
            byAttender.computeIfAbsent(assignment.getAttenderId(), id -> new ArrayList<>()).add(assignment);
        }
        return byAttender;
    }

    /**
     * The decision as section 6.2's snapshot JSON.
     *
     * <p>Built with {@link AuditDetail}, which serialises rather than concatenates. The snapshot is
     * read months later to answer "why did Ravi get this?", and a log that the thing being logged
     * can write is worth nothing.
     */
    private DispatchResult toResult(DispatchDecision decision, DispatchPolicy policy) {
        String snapshot =
                AuditDetail.of("chosen", decision.attenderId())
                        .and("score", decision.score())
                        .and(
                                "components",
                                Map.of("floor", decision.floorComponent(), "load", decision.loadComponent()))
                        .and(
                                "runnersUp",
                                decision.runnersUp().stream()
                                        .map(
                                                other ->
                                                        Map.of(
                                                                "attenderId", (Object) other.attenderId(),
                                                                "score", other.score()))
                                        .toList())
                        .and(
                                "weights",
                                Map.of("floor", policy.floorWeight(), "load", policy.loadWeight()))
                        .and("tieBreak", decision.tieBreak() == null ? null : decision.tieBreak().name())
                        .json();
        return new DispatchResult(
                decision.attenderId(), snapshot, decision.capacityExceeded(), decision.uncoveredFloor());
    }
}
