/**
 * The {@code settings} module: values the manager may change without a redeploy.
 *
 * <p>It only stores the values; each owning module ({@code dispatch}, {@code workflow}) interprets its
 * own. A key with no row falls back to {@code application.yml}.
 */
package duty.settings;
