package marionette.common;

/**
 * Spreading several departures around a point: the "fan" of petals the Explorer uses to
 * try more than one direction.
 *
 * <p>It lives here, outside the client mod, for the usual reason in this project: it is
 * pure logic, so it can be tested in milliseconds without opening a world. The first
 * branch is ALWAYS the direction that was asked for: whoever says "explore to the south
 * looking for cactus" expects the first trip to go south, not along some spread-out
 * heading.
 */
public final class Fan {

    private Fan() {}

    /**
     * How far from the origin segment n of the spiral is placed: the requested radius,
     * plus {@code grows} percent more for every segment already walked, up to the cap.
     *
     * <p>It grows on purpose instead of circling the same ring: repeating the circle
     * would mean looking at the same thing twice, and what is being looked for (a desert,
     * a jungle) is either near or far, not at exactly 300 blocks.
     *
     * @param radius radius of the first segment
     * @param branch  which one is due, from 1
     * @param grows  how much farther each segment goes than the previous one, in %
     * @param cap    maximum distance from the origin
     */
    public static int reach(int radius, int branch, int grows, int cap) {
        if (branch < 1) throw new IllegalArgumentException("branch < 1");
        double far = radius * Math.pow(1 + grows / 100.0, branch - 1);
        return (int) Math.min(cap, Math.round(far));
    }

    /**
     * @param baseX    X component of the requested direction
     * @param baseZ    Z component of the requested direction
     * @param branch   which one is due, from 1 to {@code branches}
     * @param branches how many there are in total (>= 1)
     * @return the unit vector {x, z} of that branch
     */
    public static double[] unitVector(double baseX, double baseZ, int branch, int branches) {
        if (branches < 1) throw new IllegalArgumentException("branches < 1");
        if (branch < 1 || branch > branches) {
            throw new IllegalArgumentException("branch outside 1.." + branches);
        }
        double rotation = 2 * Math.PI * (branch - 1) / branches;
        double cos = Math.cos(rotation), sen = Math.sin(rotation);
        return new double[]{baseX * cos - baseZ * sen, baseX * sen + baseZ * cos};
    }
}
