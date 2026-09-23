package masurium.bot;

import masurium.common.Route;

import java.util.ArrayList;
import java.util.List;

/**
 * Getting out of a hole, and only that.
 *
 * <p>A bot once fell into a hole with 448 dirt in its inventory and stayed there until a
 * zombie killed it. That was not a bug: it was the rule <b>building is opt-in</b> (a bot
 * that builds freely breaks and blocks things around it) taken to its last consequence.
 *
 * <p>So the exception is as narrow as it can be:
 * <ul>
 *   <li>only when there is <b>no route at all</b> to the requested destination;
 *   <li>only if the bot is enclosed on <b>all four</b> sides, which is what tells "I am
 *       in a hole" from "there is a wall ahead";
 *   <li>only <b>upwards</b> and under its own feet: never a bridge, never a block against
 *       anything of anyone's;
 *   <li>at most {@value #TALL_MAX} blocks, and only if it can get out from up there.
 * </ul>
 *
 * <p>If all of that is not met, nothing is built: the bot says it is trapped and a person
 * decides. An exception that widens itself stops being an exception.
 */
final class Rescue {

    private static final int TALL_MAX = 8;
    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private Rescue() {}

    /**
     * Is the bot stuck where walking does not get it out?
     * Enclosed on all four sides, counting as a way out both the same level and a
     * one-block step, which is what it can climb without placing anything.
     */
    static boolean inAHole(ClientWorld m, Route.Point me) {
        for (int[] side : SIDES) {
            int x = me.x() + side[0];
            int z = me.z() + side[1];
            if (m.canStand(x, me.y(), z) || m.canStand(x, me.y() + 1, z)) {
                return false;
            }
        }
        return true;
    }

    /**
     * How many blocks it has to climb to be able to walk out.
     *
     * @return the height, or -1 if no way out is seen within {@value #TALL_MAX} (with a
     *         ceiling above, for example, or really buried)
     */
    static int exitHeight(ClientWorld m, Route.Point me) {
        for (int tall = 1; tall <= TALL_MAX; tall++) {
            int y = me.y() + tall;
            // Jumping and placing a block under the feet needs room above the head: with
            // a ceiling, this climb does not exist.
            if (m.solid(me.x(), y + 1, me.z())) return -1;
            for (int[] side : SIDES) {
                if (m.canStand(me.x() + side[0], y, me.z() + side[1])) {
                    return tall;
                }
            }
        }
        return -1;
    }

    /**
     * The straight route upwards, for the usual walker to walk.
     * Its tower branch is reused instead of writing another: it is the one that already
     * knows the block is not placed at take-off but at the top of the jump, and that
     * lesson does not deserve a second, untested copy.
     */
    static List<Route.Point> staircase(Route.Point me, int tall) {
        List<Route.Point> steps = new ArrayList<>(tall + 1);
        for (int i = 0; i <= tall; i++) {
            steps.add(new Route.Point(me.x(), me.y() + i, me.z()));
        }
        return steps;
    }
}
