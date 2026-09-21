/**
 * The {@code ingestion} module (architecture section 2.3).
 *
 * Fetches raw rows from the Google Sheet or an uploaded CSV, detects deltas and normalizes
 * them into complaints.
 *
 * Invariant: a source row is never turned into two complaints.
 */
package in.ac.iitm.hostel.duty.ingestion;
