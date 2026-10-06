/**
 * The {@code ingestion} module: reads rows from the Google Sheet or an uploaded CSV and turns new ones
 * into complaints.
 *
 * <p>Invariant: a source row is never turned into two complaints.
 */
package duty.ingestion;
