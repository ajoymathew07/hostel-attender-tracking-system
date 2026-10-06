package duty.dispatch.domain;

/**
 * The floor term of the score: how close an attender works to the complaint's floor.
 *
 * <p>Distant attenders still score above zero, so the term still ranks people when the primary is
 * away.
 *
 * @param step subtracted for each floor of distance beyond adjacent
 * @param minimum the lowest value any distance gets
 */
public record FloorProximity(
        double primary, double secondary, double adjacent, double step, double minimum) {

    /**
     * How close this attender is to the work.
     *
     * @param coverage their coverage of the complaint's floor, or {@code null} if none
     * @param distance floors to the nearest floor they cover, or {@code null} if they cover none
     */
    public double score(Coverage coverage, Integer distance) {
        if (coverage == Coverage.PRIMARY) {
            return primary;
        }
        if (coverage == Coverage.SECONDARY) {
            return secondary;
        }
        if (distance == null) {
            return minimum;
        }
        return Math.max(minimum, adjacent - step * (distance - 1));
    }
}
