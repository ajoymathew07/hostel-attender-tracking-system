package duty.dispatch.application;

/**
 * The part of the heuristic a manager may change, as the rest of the system sees it (section 6.2).
 *
 * <p>A record rather than the {@code DispatchPolicy} itself, because the policy is
 * {@code dispatch.domain} and internal to this module (architecture section 2.4, rule 3). The
 * settings screen needs three numbers; it does not need the scorer.
 */
public record DispatchWeights(double floor, double load, int loadCap) {}
