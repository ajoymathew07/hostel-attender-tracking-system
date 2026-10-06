package duty.dispatch.application;

/**
 * What dispatch tells the rest of the system: who, and the snapshot that explains it.
 *
 * <p>The published shape of a decision (architecture section 2.4, rule 3). The scoring types
 * themselves stay inside {@code dispatch.domain}, so changing the formula changes nothing the
 * workflow module compiles against.
 *
 * @param scoreSnapshot the JSON of section 6.2, stored on {@code task_assignment}
 * @param capacityExceeded every eligible attender was full; the manager needs to know the queue is
 *     saturated (section 6.4)
 * @param uncoveredFloor nobody eligible covers this floor, so the choice fell to load alone
 */
public record DispatchResult(
        long attenderId, String scoreSnapshot, boolean capacityExceeded, boolean uncoveredFloor) {}
