package in.ac.iitm.hostel.duty.dispatch.domain;

import java.util.OptionalInt;
import java.util.regex.Pattern;

/**
 * The room number a resident typed, as a floor number (section 5.4's {@code floor_id} rule).
 *
 * <p>Cauvery rooms are numeric only — there is no wing letter in the number — and come in two
 * shapes, three digits (100–399) and four (1000–3099). <strong>The first digit is the floor in
 * both</strong>: 169 and 1099 are both on floor 1.
 *
 * <p>Section 5.4 states the rule as "first digit of a 3-digit room, first two of a 4-digit", which
 * maps room 1000 to floor 10 and would send every four-digit room to triage. The rule here is the
 * one the hostel actually uses; the design document is wrong on this point.
 *
 * <p>Anything else — blank, a letter, a range, free text — yields nothing, and a complaint with no
 * floor goes to triage as {@code UNMAPPED_LOCATION} rather than being dispatched on a guess
 * (section 6.2).
 */
public final class RoomFloor {

    private static final Pattern ROOM = Pattern.compile("\\d{3,4}");

    private RoomFloor() {}

    /** The floor a room number names, or empty if it is not a room number we recognise. */
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
