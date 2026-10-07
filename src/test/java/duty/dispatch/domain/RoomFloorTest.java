package duty.dispatch.domain;

import static org.assertj.core.api.Assertions.assertThat;
import duty.dispatch.domain.DispatchPolicy.RoomFloor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** The room-number rule: the first digit is the floor. */
class RoomFloorTest {

    @ParameterizedTest
    @CsvSource({"100, 1", "169, 1", "299, 2", "301, 3", "1000, 1", "1099, 1", "2050, 2", "3099, 3"})
    @DisplayName("the first digit is the floor, for three-digit and four-digit rooms alike")
    void theFirstDigitIsTheFloor(String room, int floor) {
        assertThat(RoomFloor.floorNumber(room)).hasValue(floor);
    }

    @Test
    @DisplayName("1099 and 169 land on the same floor — the case section 5.4 gets wrong")
    void fourDigitRoomsAreNotOnFloorTen() {
        assertThat(RoomFloor.floorNumber("1099")).isEqualTo(RoomFloor.floorNumber("169"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"B-204", "A102", "12", "12345", "ground floor", "101-103", "10 1"})
    @DisplayName("anything that is not a bare three or four digit number maps to no floor")
    void unreadableRoomsMapToNothing(String room) {
        assertThat(RoomFloor.floorNumber(room)).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("a missing room is not an error here — it is a complaint for triage")
    void aMissingRoomMapsToNothing(String room) {
        assertThat(RoomFloor.floorNumber(room)).isEmpty();
    }

    @Test
    @DisplayName("surrounding space is the resident's typing, not a different room")
    void spaceIsTrimmed() {
        assertThat(RoomFloor.floorNumber("  204 ")).hasValue(2);
    }
}
