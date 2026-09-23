package masurium.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How the Explorer spreads its directions. */
class FanTest {

    private static String heading(double[] v) {
        if (Math.abs(v[0]) > Math.abs(v[1])) return v[0] > 0 ? "east" : "west";
        return v[1] > 0 ? "south" : "north";
    }

    @Test
    @DisplayName("the first branch is always the requested direction")
    void firstBranchIsAlwaysRequestedDirection() {
        double[] v = Fan.unitVector(0, 1, 1, 4);
        assertEquals(0.0, v[0], 1e-9);
        assertEquals(1.0, v[1], 1e-9);
        assertEquals("south", heading(v));
    }

    @Test
    @DisplayName("four branches from the south give the four cardinal points")
    void fourBranchesFromSouthGiveFourCardinal() {
        Set<String> headings = new HashSet<>();
        for (int i = 1; i <= 4; i++) headings.add(heading(Fan.unitVector(0, 1, i, 4)));
        assertEquals(Set.of("north", "south", "east", "west"), headings);
    }

    @Test
    @DisplayName("eight branches are eight different directions, all of length 1")
    void eightBranchesAreEightDifferentDirectionsAll() {
        Set<String> views = new HashSet<>();
        for (int i = 1; i <= 8; i++) {
            double[] v = Fan.unitVector(1, 0, i, 8);
            assertEquals(1.0, Math.hypot(v[0], v[1]), 1e-9);
            // Rounded, so two equal branches really collide.
            assertTrue(views.add(String.format("%.3f,%.3f", v[0], v[1])),
                    "branch " + i + " repeats a direction");
        }
    }

    @Test
    @DisplayName("a single branch is the usual one: a round trip")
    void singleBranchIsUsualOneRoundTrip() {
        double[] v = Fan.unitVector(-1, 0, 1, 1);
        assertEquals("west", heading(v));
    }

    @Test
    @DisplayName("the spiral moves farther on each segment, starting at the requested radius")
    void spiralMovesFartherOnEachSegmentStarting() {
        assertEquals(300, Fan.reach(300, 1, 35, 1200));
        assertEquals(405, Fan.reach(300, 2, 35, 1200));
        assertEquals(547, Fan.reach(300, 3, 35, 1200));
        assertEquals(738, Fan.reach(300, 4, 35, 1200));
        // Each segment farther than the previous one: if this breaks, the spiral turns
        // into circling the same ring.
        int before = 0;
        for (int i = 1; i <= 8; i++) {
            int now = Fan.reach(150, i, 35, 100000);
            assertTrue(now > before, "segment " + i + " does not move farther");
            before = now;
        }
    }

    @Test
    @DisplayName("the cap cuts the spiral: it does not go to the end of the world")
    void capCutsSpiralItDoesNotGo() {
        assertEquals(1200, Fan.reach(300, 20, 35, 1200));
        assertEquals(500, Fan.reach(900, 1, 35, 500));
    }

    @Test
    @DisplayName("without growth, every segment goes to the same radius")
    void withoutGrowthEverySegmentGoesToSame() {
        for (int i = 1; i <= 4; i++) assertEquals(300, Fan.reach(300, i, 0, 1200));
    }

    @Test
    @DisplayName("asking for a branch that does not exist is rejected, not invented")
    void askingForBranchThatDoesNotExist() {
        assertThrows(IllegalArgumentException.class, () -> Fan.unitVector(0, 1, 0, 4));
        assertThrows(IllegalArgumentException.class, () -> Fan.unitVector(0, 1, 5, 4));
        assertThrows(IllegalArgumentException.class, () -> Fan.unitVector(0, 1, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> Fan.reach(300, 0, 35, 1200));
    }
}
