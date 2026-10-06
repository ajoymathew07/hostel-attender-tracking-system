package duty.dispatch.domain;

import java.util.OptionalInt;
import java.util.regex.Pattern;

/**
 * The floor number a room number names: its first digit.
 *
 * <p>Cauvery rooms are digits only, three or four long (169 and 1099 are both floor 1). The design doc
 * says "first two digits of a 4-digit room", which is wrong. Anything else returns empty, and the
 * complaint goes to triage.
 */
public final class RoomFloor {

    private static final Pattern ROOM = Pattern.compile("\\d{3,4}");

    private RoomFloor() {}

    /** The floor a room number names, or empty if it is not a recognised room number. */
    public static OptionalInt floorNumber(String room) {
        if (room == null) {
            return OptionalInt.empty();
        }
        String trimmed = room.trim();
        if (!ROOM.matcher(trimmed).matches()) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(trimmed.charAt(0) - '0');
    }
}
