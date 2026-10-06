/**
 * The {@code audit} module (architecture section 2.3).
 *
 * The append-only event log.
 *
 * Invariant: an audit row is written in the same transaction as the change it records.
 */
package duty.audit;
