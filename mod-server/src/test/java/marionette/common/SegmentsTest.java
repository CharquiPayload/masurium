package marionette.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The bunny hop condition: jumping only happens on a flat straight line. */
class SegmentsTest {

    private static List<Route.Point> route(int[][] steps) {
        return java.util.Arrays.stream(steps)
                .map(c -> new Route.Point(c[0], c[1], c[2]))
                .toList();
    }

    @Test
    @DisplayName("a flat straight line allows jumping")
    void flatStraightLineAllowsJumping() {
        var r = route(new int[][]{{0, 64, 0}, {1, 64, 0}, {2, 64, 0}, {3, 64, 0}});
        assertTrue(Segments.flatStraight(r, 0, 2));
        assertTrue(Segments.flatStraight(r, 1, 2));
    }

    @Test
    @DisplayName("a curve does not: the jump would overshoot the point")
    void curveDoesNotJumpWouldOvershootPoint() {
        var r = route(new int[][]{{0, 64, 0}, {1, 64, 0}, {1, 64, 1}, {1, 64, 2}});
        assertFalse(Segments.flatStraight(r, 0, 2));
    }

    @Test
    @DisplayName("a step neither, going up nor down")
    void stepNeitherGoingUpNorDown() {
        var climbs = route(new int[][]{{0, 64, 0}, {1, 65, 0}, {2, 65, 0}});
        assertFalse(Segments.flatStraight(climbs, 0, 2));
        var low = route(new int[][]{{0, 64, 0}, {1, 64, 0}, {2, 63, 0}});
        assertFalse(Segments.flatStraight(low, 0, 2));
    }

    @Test
    @DisplayName("without points ahead there is no jumping: nothing to check against")
    void withoutPointsAheadThereIsNoJumping() {
        var r = route(new int[][]{{0, 64, 0}, {1, 64, 0}, {2, 64, 0}});
        assertFalse(Segments.flatStraight(r, 1, 2));   // only one left
        assertFalse(Segments.flatStraight(r, 2, 1));   // already the last one
    }

    @Test
    @DisplayName("a constant diagonal IS straight")
    void constantDiagonalIsStraight() {
        var r = route(new int[][]{{0, 64, 0}, {1, 64, 1}, {2, 64, 2}, {3, 64, 3}});
        assertTrue(Segments.flatStraight(r, 0, 2));
    }

    @Test
    @DisplayName("odd inputs are rejected instead of blowing up")
    void oddInputsAreRejectedInsteadOfBlowing() {
        var r = route(new int[][]{{0, 64, 0}, {0, 64, 0}, {0, 64, 0}});
        assertFalse(Segments.flatStraight(r, 0, 2));   // two identical points
        assertFalse(Segments.flatStraight(null, 0, 2));
        assertFalse(Segments.flatStraight(r, -1, 2));
        assertFalse(Segments.flatStraight(r, 0, 0));
    }
}
