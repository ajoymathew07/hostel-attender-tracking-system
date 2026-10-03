package in.ac.iitm.hostel.duty.dispatch.domain;

/**
 * The {@code Floor(a, c)} term of section 6.2, as its table of values.
 *
 * <p>Every value is in [0, 1] and distant attenders score above zero deliberately: when the
 * primary is unavailable somebody still has to go, and a zero would make the term uninformative in
 * exactly that case.
 *
 * @param primary {@code PRIMARY} coverage of the complaint's own floor
 * @param secondary {@code SECONDARY} coverage of it
 * @param adjacent one floor away
 * @param step subtracted for each further floor of distance
 * @param minimum the value no distance falls below
 */
public record FloorProximity(
        double primary, double secondary, double adjacent, double step, double minimum) {

    /**
     * How close this attender is to the work.
     *
     * @param coverage their coverage of the complaint's floor, or {@code null} if they have none
     * @param distance floors to the nearest floor they do cover, or {@code null} if they cover none
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
