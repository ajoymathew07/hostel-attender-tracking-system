package duty.workflow.application;

/**
 * What a caller gets back after a complaint is created: enough to link the source row to it and to
 * tell the manager what happened, and nothing more.
 *
 * @param id the complaint's database id
 * @param publicRef the human-facing reference, e.g. {@code CAU-2026-0417}
 */
public record IngestedComplaint(long id, String publicRef) {}
