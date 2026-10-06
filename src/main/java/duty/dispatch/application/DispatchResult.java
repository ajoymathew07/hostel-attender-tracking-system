package duty.dispatch.application;

/**
 * What dispatch returns to the rest of the system: who was chosen, and a JSON snapshot explaining why.
 *
 * @param capacityExceeded every eligible attender was already full
 * @param uncoveredFloor nobody eligible covers this floor, so the choice was made on load alone
 */
public record DispatchResult(
        long attenderId, String scoreSnapshot, boolean capacityExceeded, boolean uncoveredFloor) {}
