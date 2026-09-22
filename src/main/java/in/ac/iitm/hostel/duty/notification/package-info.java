/**
 * The {@code notification} module (architecture section 2.3).
 *
 * Fan-out of domain events to manager SSE streams and attender Web Push, on AFTER_COMMIT.
 *
 * Invariant: delivery is best-effort and never blocks or rolls back a transaction.
 */
package in.ac.iitm.hostel.duty.notification;
