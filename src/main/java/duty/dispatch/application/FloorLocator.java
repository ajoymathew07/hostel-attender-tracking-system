package duty.dispatch.application;

import duty.dispatch.domain.RoomFloor;
import duty.dispatch.infrastructure.FloorRepository;
import java.util.Optional;
import java.util.OptionalInt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Turns the room number a resident typed into a {@code floor_id}.
 *
 * <p>Room numbers carry no wing, so the wing comes from configuration (the hostel has one).
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
