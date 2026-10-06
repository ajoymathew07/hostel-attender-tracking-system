package duty.ingestion.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Identity and change detection, both derived from content (section 5.3).
 *
 * <pre>
 * response_key = SHA256( normalize(timestamp) || "\x1f" || normalize(email) )
 * content_hash = SHA256( join("\x1f", [normalize(cell) for cell in row]) )
 * </pre>
 *
 * <p>Splitting the two is what makes the hard cases tractable. {@code response_key} is stable
 * across row shifts and across edits, so a deleted row that shifts every row below it produces
 * zero spurious complaints; {@code content_hash} changes whenever any answer changes, which is how
 * an edited response is recognised as the same complaint rather than a new one.
 *
 * <p>The unit separator is the join character because it is the one byte a Google Form answer
 * cannot contain, so no combination of cells can be made to hash as a different combination.
 */
public final class RowDigest {

    /** ASCII US — not a character a form answer can carry, so cell boundaries cannot be forged. */
    private static final String UNIT_SEPARATOR = "\u001f";

    private RowDigest() {}

    /**
     * Folds the differences that are not answers: absence, padding, doubled whitespace and
     * Unicode compatibility forms.
     *
     * <p>It deliberately does <em>not</em> fold case. {@code content_hash} must notice every real
     * edit, and a resident correcting their capitalisation has edited their answer.
     */
    public static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFKC).trim().replaceAll("\\s+", " ");
    }

    /**
     * The stable identity of one response.
     *
     * <p>The email is lower-cased here and nowhere else: addresses are case-insensitive, so the
     * same person writing {@code Ravi@} and {@code ravi@} is one submitter, not two.
     *
     * @param ordinal 0 for the first occurrence within a batch; section 5.3's in-batch
     *     disambiguator for two submissions in the same second by the same person. Appending it
     *     only when it is non-zero keeps the ordinary key exactly the design's formula.
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
