/**
 * The {@code settings} module — what the manager may change without a redeploy (section 10.4's
 * {@code /settings} screen).
 *
 * <p>Not one of the modules architecture section 2.3 names, and here for a reason the design
 * states repeatedly elsewhere: section 4.5 says the working calendar and the SLA table are
 * "configurable by the manager rather than compiled in", section 8.2 says the targets "are
 * configuration, not constants", and section 6.2's R5 says the dispatch weights must be tunable
 * without a redeploy. Those live in three different modules, and one key-value table that three
 * modules write to is not a table any one of them can own.
 *
 * <p>So this module owns the storage and nothing else. The <em>meaning</em> of each value stays
 * with the module it belongs to — {@code dispatch} knows what a weight is, {@code workflow} knows
 * what an SLA window is — and each reads its own through {@code SettingsStore}. The YAML remains
 * the default; a row here overrides it.
 *
 * <p>Invariant: a value nobody has overridden reads exactly as the YAML says.
 */
package duty.settings;
