/**
 * The {@code analytics} module: resolution time, the bottleneck stage and anomaly alerts (section 8).
 *
 * <p>Read-only over the complaint and audit tables. The only thing it writes is its own
 * {@code analytics_alert} rows, and an alert is advice for the manager; nothing acts on it (8.5).
 */
package duty.analytics;
