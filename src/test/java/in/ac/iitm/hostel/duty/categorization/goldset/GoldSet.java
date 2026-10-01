package in.ac.iitm.hostel.duty.categorization.goldset;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The labelled corpus of test plan section 12.3 and its development / holdout split.
 *
 * <p><strong>The split is derived from the text, not stored beside it.</strong> There is no split
 * column in {@code complaints.psv}, so nobody chose which items the holdout holds — which is the
 * only property that makes a holdout worth having. Section 12.3 is explicit that tuning against the
 * holdout produces a number that means nothing, and a split someone curated is tuning by another
 * name.
 *
 * <p>{@link String#hashCode()} is specified by the JDK, not left to the implementation, so the same
 * corpus partitions the same way on every machine and in every run. Adding an item later moves only
 * that item.
 */
final class GoldSet {

    /** Section 12.3's fifth label: a human could not place it either, so triage is the right answer. */
    static final String AMBIGUOUS = "AMBIGUOUS";

    private static final String RESOURCE = "/goldset/complaints.psv";

    /** "Split 70/30 into development and holdout" — seven buckets of ten. */
    private static final int DEVELOPMENT_BUCKETS = 7;

    private GoldSet() {}

    /**
     * One labelled complaint.
     *
     * @param label the human's answer: a domain name, or {@link #AMBIGUOUS}
     * @param text the complaint as a resident would have typed it
     */
    record Item(String label, String text) {

        boolean expectsTriage() {
            return AMBIGUOUS.equals(label);
        }

        boolean isDevelopment() {
            return Math.floorMod(text.hashCode(), 10) < DEVELOPMENT_BUCKETS;
        }
    }

    static List<Item> development() {
        return load().stream().filter(Item::isDevelopment).toList();
    }

    static List<Item> holdout() {
        return load().stream().filter(item -> !item.isDevelopment()).toList();
    }

    /** Every item, in file order. Blank lines and `#` comments are the file's own documentation. */
    static List<Item> load() {
        try (InputStream stream = GoldSet.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("gold set missing from the classpath: " + RESOURCE);
            }
            try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                return reader
                        .lines()
                        .filter(line -> !line.isBlank() && !line.startsWith("#"))
                        .map(GoldSet::parse)
                        .toList();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Item parse(String line) {
        String[] fields = line.split("\\|", 2);
        if (fields.length != 2) {
            throw new IllegalStateException("gold set line is not LABEL|text: " + line);
        }
        return new Item(fields[0].trim(), fields[1].trim());
    }
}
