package marionette.common;

import java.util.List;

/**
 * Questions about a piece of route that do not need the world: they live here so they can
 * be tested without opening Minecraft.
 */
public final class Segments {

    private Segments() {}

    /**
     * Do the next {@code quantity} steps go in a straight line and at the same height?
     *
     * <p>The Walker's bunny hop asks this: a running jump covers about four blocks with
     * no brakes, so on a curve or a step it overshoots the point and has to come back,
     * which is slower than not jumping at all.
     *
     * @param route    the whole route
     * @param from     index of the point being walked to now
     * @param quantity steps ahead that must keep the same direction
     * @return false also when there are not that many points ahead: with no room to check
     *         it, no jump
     */
    public static boolean flatStraight(List<Route.Point> route, int from, int quantity) {
        if (route == null || quantity < 1 || from < 0) return false;
        if (from + quantity >= route.size()) return false;
        Route.Point here = route.get(from);
        Route.Point one = route.get(from + 1);
        if (one.y() != here.y()) return false;
        int dx = one.x() - here.x(), dz = one.z() - here.z();
        if (dx == 0 && dz == 0) return false;      // two identical points: odd
        for (int i = 2; i <= quantity; i++) {
            Route.Point before = route.get(from + i - 1);
            Route.Point now = route.get(from + i);
            if (now.y() != before.y()) return false;
            if (now.x() - before.x() != dx || now.z() - before.z() != dz) {
                return false;
            }
        }
        return true;
    }
}
