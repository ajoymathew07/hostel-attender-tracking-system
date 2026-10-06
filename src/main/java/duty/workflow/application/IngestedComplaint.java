package duty.workflow.application;

/**
 * Returned after a complaint is created.
 *
 * @param publicRef the human-facing reference, e.g. {@code CAU-2026-0417}
 */
public record IngestedComplaint(long id, String publicRef) {}
