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
 * Filters out unavailable attenders, then asks {@link DispatchPolicy} to score the rest.
 *
 * <p>Availability is a filter, not part of the score, so a good floor match can never outweigh
 * someone being on leave. This class only gathers the inputs; the scoring is pure.
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
     * Who should do the work on this floor. Empty when nobody is eligible; the caller then sends the
     * complaint to triage.
     *
     * @param alreadyAssigned attenders who already hold this complaint and cannot be given it again
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
     * Available unless a leave or off-duty window covers {@code now}. No window means available.
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
     * Builds the result, with the decision as JSON so the manager can later see why this attender was
     * chosen.
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
