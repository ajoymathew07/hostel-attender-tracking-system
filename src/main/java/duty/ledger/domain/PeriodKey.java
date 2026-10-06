package duty.ledger.domain;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * Derivation of {@code point_ledger.period_key} from an effective date (section 7.9).
 *
 * <p>The period is derived from the <strong>Asia/Kolkata business date</strong>, not from the UTC
 * instant (NFR-7). A task resolved at 02:00 IST on 1 October belongs to October, which is what the
 * manager expects; storing UTC and grouping naively would have silently filed it under September
 * (test plan E21).
 */
public final class PeriodKey {

    /** The business timezone. Timestamps are stored UTC; the *business date* is local. */
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Kolkata");

    /** Width of a {@code YYYY-MM} key, matching the column. */
    public static final int LENGTH = 7;

    private PeriodKey() {}

    /** {@code 'YYYY-MM'} for a business date. */
    public static String of(LocalDate effectiveDate) {
        Objects.requireNonNull(effectiveDate, "effectiveDate");
        return String.format("%04d-%02d", effectiveDate.getYear(), effectiveDate.getMonthValue());
    }

    /** The Asia/Kolkata business date of a UTC instant. */
    public static LocalDate businessDateOf(java.time.Instant instant) {
        Objects.requireNonNull(instant, "instant");
        return ZonedDateTime.ofInstant(instant, BUSINESS_ZONE).toLocalDate();
    }

    /** {@code 'YYYY-MM'} for the business date on which an instant falls. */
    public static String ofInstant(java.time.Instant instant) {
        return of(businessDateOf(instant));
    }
}
