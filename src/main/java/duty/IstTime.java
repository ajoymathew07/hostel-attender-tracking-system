package duty;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Shows a stored UTC instant as India time on the pages, e.g. {@code 13 Oct 2026, 10:22 AM}.
 * Templates call it as {@code ${@ist.format(...)}}.
 *
 * <p>Why: the database and the server run in UTC, so a raw {@link Instant} prints as
 * {@code 2026-10-13T04:52:53Z}, which the manager and attenders should not have to convert.
 */
@Component("ist")
public class IstTime {

    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.ENGLISH)
                    .withZone(ZoneId.of("Asia/Kolkata"));

    /** The instant in India time, or {@code null} for no instant so a template can show a fallback. */
    public String format(Instant instant) {
        return instant == null ? null : FORMAT.format(instant);
    }
}
