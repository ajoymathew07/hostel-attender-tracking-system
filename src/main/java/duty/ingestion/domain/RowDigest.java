package duty.ingestion.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Computes a row's identity and its change-detection hash.
 *
 * <pre>
 * response_key = SHA256( normalize(timestamp) || "\x1f" || normalize(email) )
 * content_hash = SHA256( join("\x1f", [normalize(cell) for cell in row]) )
 * </pre>
 *
 * <p>{@code response_key} does not depend on row position, so deleting a sheet row creates no
 * duplicates. {@code content_hash} changes when any answer is edited.
 */
public final class RowDigest {

    /**
     * ASCII unit separator: a form answer cannot contain it, so cell boundaries cannot be faked.
     */
    private static final String UNIT_SEPARATOR = "\u001f";

    private RowDigest() {}

    /**
     * Ignores missing values, extra whitespace and Unicode compatibility forms. Case is kept, because a
     * capitalisation fix is a real edit.
     */
    public static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFKC).trim().replaceAll("\\s+", " ");
    }

    /**
     * The stable identity of one response. The email is lower-cased since addresses are
     * case-insensitive.
     *
     * @param ordinal 0 normally; higher for repeat submissions by the same person in the same second
     */
    public static String responseKey(String timestamp, String email, int ordinal) {
        String material =
                normalize(timestamp) + UNIT_SEPARATOR + normalize(email).toLowerCase(Locale.ROOT);
        if (ordinal > 0) {
            material = material + UNIT_SEPARATOR + ordinal;
        }
        return sha256(material);
    }

    /** Changes whenever any answer changes, and only then. */
    public static String contentHash(List<String> cells) {
        StringBuilder material = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                material.append(UNIT_SEPARATOR);
            }
            material.append(normalize(cells.get(i)));
        }
        return sha256(material.toString());
    }

    private static String sha256(String material) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            // SHA-256 is required of every Java platform; there is no recovery and no fallback
            // worth having, because a weaker digest would silently weaken the idempotency key.
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
