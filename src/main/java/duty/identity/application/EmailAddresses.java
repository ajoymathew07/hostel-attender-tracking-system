package duty.identity.application;

import duty.identity.domain.AppUser;
import java.util.regex.Pattern;

/**
 * Validation for allowlist email addresses.
 *
 * <p>{@link #requireAllowlistAddress} throws, so a manager is told about a typo. {@link #isAscii}
 * returns a boolean because the sign-in path must not throw (a throw would roll back the audit row).
 * Why ASCII only: lower-casing some Unicode characters (e.g. the Kelvin sign) produces a plain
 * {@code k}, which could match someone else's address.
 */
final class EmailAddresses {

    /**
     * Stricter than RFC 5321 on purpose: no quoted local parts, spaces or backslashes. A Google account
     * never needs them.
     */
    private static final Pattern ALLOWLIST_ADDRESS =
            Pattern.compile("[A-Za-z0-9!#$%&'*+/=?^_`{|}~.-]{1,64}@[A-Za-z0-9.-]{1,185}\\.[A-Za-z]{2,}");

    /** {@code app_user.email} is {@code VARCHAR(255)}. */
    private static final int MAX_LENGTH = 255;

    private EmailAddresses() {}

    /**
     * The normalised address, or {@link InvalidEmailException} if it is not acceptable as an allowlist
     * key.
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
