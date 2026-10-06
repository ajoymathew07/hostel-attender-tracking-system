package duty.identity.application;

import duty.identity.domain.AppUser;
import java.util.regex.Pattern;

/**
 * What may be an allowlist key.
 *
 * <p>Two separate jobs, deliberately not merged:
 *
 * <ul>
 *   <li>{@link #requireAllowlistAddress(String)} guards what a manager may <em>write</em> into
 *       {@code app_user.email}. It throws, because a manager typing nonsense should be told so
 *       rather than silently locking somebody out of the system for good.
 *   <li>{@link #isAscii(String)} guards what the allowlist will <em>match</em>. It returns a
 *       boolean, because the sign-in path must never throw: a refusal there has to be audited, and
 *       an exception inside that transaction would roll the audit row back (see {@link
 *       SignInOutcome}).
 * </ul>
 *
 * <p><strong>Why ASCII.</strong> {@link AppUser#normalizeEmail} lower-cases, and lower-casing is
 * not injective over Unicode: {@code U+212A KELVIN SIGN} folds to {@code k}, so
 * {@code Kumar@example.com} normalises to exactly {@code kumar@example.com} and would match a
 * row it is not. No Google account can hold such an address, so this is theory rather than a live
 * hole — but the allowlist is the access-control boundary of the whole system (section 11.2) and
 * "the provider would never send that" is not a property we control. Restricting both sides to
 * ASCII makes the folding injective again, and fails closed either way.
 */
final class EmailAddresses {

    /**
     * Deliberately stricter than RFC 5321. Hibernate Validator's {@code @Email} accepts a quoted
     * local part, so {@code "a\"b"@example.com} is a valid address that carries a double quote into
     * the audit log. An allowlist key is a Google account, not an arbitrary mailbox, so quoted
     * local parts, spaces and backslashes are simply not addresses this system will store.
     */
    private static final Pattern ALLOWLIST_ADDRESS =
            Pattern.compile("[A-Za-z0-9!#$%&'*+/=?^_`{|}~.-]{1,64}@[A-Za-z0-9.-]{1,185}\\.[A-Za-z]{2,}");

    /** {@code app_user.email} is {@code VARCHAR(255)}. */
    private static final int MAX_LENGTH = 255;

    private EmailAddresses() {}

    /**
     * The normalised address, or {@link InvalidEmailException} if it is not one this system will
     * accept as an allowlist key. Called from the service rather than the controller on purpose: a
     * check that lives only in a controller is one refactor away from being bypassed (section 11.3).
     */
    static String requireAllowlistAddress(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidEmailException("");
        }
        // Checked before normalising, because normalising is what folds a non-ASCII character onto
        // an ASCII one; afterwards the evidence is gone and the pattern below would be satisfied.
        if (!isAscii(raw)) {
            throw new InvalidEmailException(raw.trim());
        }
        String normalised = AppUser.normalizeEmail(raw);
        if (normalised.length() > MAX_LENGTH || !ALLOWLIST_ADDRESS.matcher(normalised).matches()) {
            throw new InvalidEmailException(normalised);
        }
        return normalised;
    }

    /** Whether every character is ASCII, so that case folding cannot manufacture a match. */
    static boolean isAscii(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) > 0x7F) {
                return false;
            }
        }
        return true;
    }
}
