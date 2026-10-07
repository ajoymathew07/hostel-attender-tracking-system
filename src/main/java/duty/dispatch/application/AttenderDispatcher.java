package duty.dispatch.application;

import duty.audit.application.AuditTrail.AuditDetail;
import duty.dispatch.domain.DispatchPolicy.DispatchCandidate;
import duty.dispatch.domain.DispatchPolicy.DispatchDecision;
import duty.dispatch.domain.DispatchPolicy.FloorProximity;
import duty.dispatch.domain.DispatchPolicy.RoomFloor;
import duty.dispatch.domain.DispatchPolicy;
import duty.dispatch.domain.FloorAssignment.Coverage;
import duty.dispatch.domain.FloorAssignment;
import duty.dispatch.infrastructure.AvailabilityWindowRepository;
import duty.dispatch.infrastructure.FloorAssignmentRepository;
import duty.dispatch.infrastructure.FloorRepository;
import duty.identity.application.UserProvisioningService;
import duty.settings.application.SettingsStore.Settings;
import duty.settings.application.SettingsStore;
import duty.workflow.application.AttenderWorkloadService.AttenderWorkload;
import duty.workflow.application.AttenderWorkloadService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
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

    /**
     * What dispatch returns to the rest of the system: who was chosen, and a JSON snapshot explaining why.
     *
     * @param capacityExceeded every eligible attender was already full
     * @param uncoveredFloor nobody eligible covers this floor, so the choice was made on load alone
     */
    public record DispatchResult(
            long attenderId, String scoreSnapshot, boolean capacityExceeded, boolean uncoveredFloor) {}

    /**
     * Turns the room number a resident typed into a {@code floor_id}.
     *
     * <p>Room numbers carry no wing, so the wing comes from configuration (the hostel has one).
     */
    @Service
    public static class FloorLocator {

        private final FloorRepository floors;
        private final String wing;

        FloorLocator(FloorRepository floors, @Value("${dispatch.wing}") String wing) {
            this.floors = floors;
            this.wing = wing;
        }

        /** Empty when the room is unreadable or names a floor this hostel does not have. */
        public Optional<Long> floorIdFor(String room) {
            OptionalInt floorNumber = RoomFloor.floorNumber(room);
            if (floorNumber.isEmpty()) {
                return Optional.empty();
            }
            return floors.findByWingAndFloorNumber(wing, floorNumber.getAsInt()).map(floor -> floor.getId());
        }
    }

    /**
     * The dispatch weights currently in force: the YAML defaults, overridden by the settings screen.
     *
     * <p>Read on every dispatch, so a change applies to the next complaint without a restart. The
     * proximity table is not editable because only the order of its values matters.
     */
    @Service
    public static class DispatchSettings {

        public static final String FLOOR_WEIGHT = "dispatch.weights.floor";
        public static final String LOAD_WEIGHT = "dispatch.weights.load";
        public static final String LOAD_CAP = "dispatch.load-cap";

        private final DispatchPolicy defaults;
        private final SettingsStore store;

        DispatchSettings(DispatchPolicy defaults, SettingsStore store) {
            this.defaults = defaults;
            this.store = store;
        }

        /** The three editable numbers, for the settings screen. */
        public DispatchWeights current() {
            DispatchPolicy policy = from(store.current());
            return new DispatchWeights(policy.floorWeight(), policy.loadWeight(), policy.loadCap());
        }

        /** The full scorer, for the dispatcher. */
        DispatchPolicy policy() {
            return from(store.current());
        }

        DispatchPolicy from(Settings settings) {
            return new DispatchPolicy(
                    settings.number(FLOOR_WEIGHT, defaults.floorWeight()),
                    settings.number(LOAD_WEIGHT, defaults.loadWeight()),
                    settings.integer(LOAD_CAP, defaults.loadCap()),
                    defaults.proximity());
        }

        /**
         * Validates new weights and returns them as settings rows. Refuses weights that do not sum to 1
         * rather than rescaling them.
         *
         * <p>Returns rows instead of saving them so the settings screen can save all sections in one
         * transaction.
         */
        public Map<String, String> edits(double floorWeight, double loadWeight, int loadCap) {
            if (Math.abs(floorWeight + loadWeight - 1) > 1e-9) {
                throw new IllegalArgumentException(
                        "the floor and load weights must sum to 1, but sum to " + (floorWeight + loadWeight));
            }
            if (loadCap < 1) {
                throw new IllegalArgumentException("the load cap must be at least 1");
            }
            return Map.of(
                    FLOOR_WEIGHT, String.valueOf(floorWeight),
                    LOAD_WEIGHT, String.valueOf(loadWeight),
                    LOAD_CAP, String.valueOf(loadCap));
        }

        /** The fixed proximity table, for the settings screen to display. */
        public ProximityTable proximity() {
            FloorProximity table = defaults.proximity();
            return new ProximityTable(
                    table.primary(), table.secondary(), table.adjacent(), table.step(), table.minimum());
        }
    }

    /** The weights a manager may change: floor weight, load weight and load cap. */
    public record DispatchWeights(double floor, double load, int loadCap) {}

    /** The floor-proximity values, so the settings screen can show them (read-only). */
    public record ProximityTable(
            double primary, double secondary, double adjacent, double step, double minimum) {}
}
