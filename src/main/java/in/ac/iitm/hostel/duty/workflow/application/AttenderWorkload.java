package in.ac.iitm.hostel.duty.workflow.application;

import java.time.Instant;

/**
 * How much work one attender is carrying, and how recently they were given any — the inputs to
 * {@code Load(a)} and to the tie-break chain (section 6.2).
 *
 * <p>Three numbers rather than the assignments themselves: dispatch may not see
 * {@code workflow.domain} (architecture section 2.4, rule 3), and these are the whole of what the
 * heuristic reads.
 *
 * @param weighted {@code L(a)} — 3 per P1, 2 per P2, 1 per P3 over their active tasks
 * @param assignedToday how many assignments they were given today, in the working calendar's zone
 * @param lastAssignedAt {@code null} if they have never held one
 */
public record AttenderWorkload(int weighted, long assignedToday, Instant lastAssignedAt) {}
