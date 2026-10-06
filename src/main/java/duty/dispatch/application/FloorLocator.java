package duty.dispatch.application;

import duty.dispatch.domain.RoomFloor;
import duty.dispatch.infrastructure.FloorRepository;
import java.util.Optional;
import java.util.OptionalInt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The room number a resident typed, as a {@code floor_id} (section 5.4).
 *
 * <p>Published from the dispatch module because {@code floor} is this module's table: ingestion
 * records the room, and what that room means is a location question (architecture section 2.4,
 * rule 3).
 *
 * <p>A Cauvery room number names a floor but not a wing, while {@code floor} is keyed
 * {@code uq_floor (wing, floor_number)}. The hostel is deployed as one wing, so the wing is
 * configuration rather than something to parse out of a number that does not carry it.
 */
@Service
public class FloorLocator {

    private final FloorRepository floors;
    private final String wing;

    FloorLocator(FloorRepository floors, @Value("${dispatch.wing}") String wing) {
        this.floors = floors;
        this.wing = wing;
    }

    /** Empty when the room is unreadable or names a floor this hostel does not have. */
    public Optional<Long> floorIdFor(String room) {
        OptionalInt floorNumber = RoomFloor.floorNumber(room);
        if (floorNumber.isEmpty()) {
            return Optional.empty();
        }
        return floors.findByWingAndFloorNumber(wing, floorNumber.getAsInt()).map(floor -> floor.getId());
    }
}
