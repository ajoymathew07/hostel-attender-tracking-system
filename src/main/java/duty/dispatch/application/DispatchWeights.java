package duty.dispatch.application;

/** The weights a manager may change: floor weight, load weight and load cap. */
public record DispatchWeights(double floor, double load, int loadCap) {}
