package marionette.server;

import marionette.common.TextWorld;
import marionette.common.Route;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The path finder, tested against worlds drawn with text.
 * It lives in mod-server's tests because that is where JUnit is set up, but what it tests
 * is {@code common/}, which both mods compile.
 *
 * <p><b>Careful when drawing a map</b>: the player is TWO blocks tall, so every walkable
 * tile needs air above. And outside the drawing counts as rock, so an extra layer of air
 * must be left on top or nowhere can be stood on (caught while writing these very tests).
 * The one that justifies the whole file is {@link #cliffBugItPrefersStairsToPlunging}: the cliff
 * bug, checked without opening the game.
 */
class RouteTest {

    private static Route.Result search(TextWorld m) {
        return Route.search(m, m.exitPoint(), m.goal());
    }

    @Test
    @DisplayName("in open field it goes straight")
    void inOpenFieldItGoesStraight() {
        TextWorld m = TextWorld.of(0,
                new String[]{"#####", "#####", "#####"},
                new String[]{".....", "S...G", "....."},
                new String[]{".....", ".....", "....."},
                new String[]{".....", ".....", "....."});
        Route.Result r = search(m);
        assertTrue(r.hasRoute(), r.reason());
        assertEquals(m.exitPoint(), r.steps().get(0));
        assertEquals(m.goal(), r.steps().get(r.steps().size() - 1));
        assertEquals(5, r.steps().size(), "4 steps in a line, no detours");
    }

    @Test
    @DisplayName("it goes around a wall instead of through it")
    void itGoesAroundWallInsteadOfThrough() {
        // A two-high wall (it cannot go over it) with a gap to the north.
        TextWorld m = TextWorld.of(0,
                new String[]{"#######", "#######", "#######", "#######"},
                new String[]{".......", "..#....", "S.#..G.", "..#...."},
                new String[]{".......", "..#....", "..#....", "..#...."},
                new String[]{".......", ".......", ".......", "......."},
                new String[]{".......", ".......", ".......", "......."});
        Route.Result r = search(m);
        assertTrue(r.hasRoute(), r.reason());
        for (Route.Point p : r.steps()) {
            assertFalse(m.solid(p.x(), p.y(), p.z()),
                    "the route goes through the wall at " + p);
        }
        // And it really went around: it had to go north (z=0).
        assertTrue(r.steps().stream().anyMatch(p -> p.z() == 0),
                "it did not go around through the gap; route: " + r.steps());
    }

    @Test
    @DisplayName("if the wall closes the way, it says so and invents no route")
    void ifWallClosesWayItSaysSo() {
        TextWorld m = TextWorld.of(0,
                new String[]{"#####", "#####", "#####"},
                new String[]{"..#..", "S.#.G", "..#.."},
                new String[]{"..#..", "..#..", "..#.."},
                new String[]{".....", ".....", "....."},
                new String[]{".....", ".....", "....."});
        Route.Result r = search(m);
        assertFalse(r.hasRoute(), "there should be no route");
        assertTrue(r.reason().contains("there is no route"), r.reason());
        assertTrue(r.looked() > 0, "it had to explore before giving up");
    }

    @Test
    @DisplayName("THE CLIFF BUG: it prefers the stairs to plunging")
    void cliffBugItPrefersStairsToPlunging() {
        // A plateau to the east (y=4) and a hollow to the west (y=1). From S there are
        // two paths: dropping 3 blocks at once, or going down one at a time along the
        // south. With a flat fall cost, A* picks the drop: it arrives just the same and
        // in fewer steps. This test pins that it does not.
        TextWorld m = TextWorld.of(0,
                new String[]{"#######", "#######", "#######"},   // y=0 rock
                new String[]{"....###", "G...###", "...####"},   // y=1 hollow
                new String[]{"....###", "....###", "....###"},   // y=2
                new String[]{"....###", "....###", ".....##"},   // y=3
                new String[]{".......", ".....S.", "......."},   // y=4 plateau
                new String[]{".......", ".......", "......."},
                new String[]{".......", ".......", "......."});
        Route.Result r = search(m);
        assertTrue(r.hasRoute(), r.reason());

        // Not the exact route is checked, but the property that matters: it never drops
        // more than is reasonable at once.
        for (int i = 1; i < r.steps().size(); i++) {
            int descent = r.steps().get(i - 1).y() - r.steps().get(i).y();
            assertTrue(descent <= 2, "it dropped " + descent + " blocks at once, "
                    + "from step " + r.steps().get(i - 1) + " to " + r.steps().get(i)
                    + "; full route: " + r.steps());
        }
    }

    @Test
    @DisplayName("falling into water is fine from any height: water cancels the damage")
    void fallingIntoWaterIsFineFromAny() {
        // A four-block fall, one more than accepted on dry ground. With water at the
        // bottom it has to be possible anyway.
        TextWorld m = TextWorld.of(0,
                new String[]{"#####", "#####"},
                new String[]{"#G~~#", "#####"},   // y=1 water below the drop
                new String[]{"#...#", "#####"},
                new String[]{"#...#", "#####"},
                new String[]{"#..##", "#####"},   // y=4 ledge
                new String[]{"#..S#", "#####"},   // y=5 start, 4 above the water
                new String[]{"#...#", "#####"},
                new String[]{"#...#", "#####"});
        Route.Result r = Route.search(m, m.exitPoint(), m.goal(),
                new Route.Options(3, 20_000));
        assertTrue(r.hasRoute(),
                "with water at the bottom it should be able to go down even beyond the limit: "
                + r.reason());
    }

    @Test
    @DisplayName("it climbs a one-block step")
    void itClimbsOneBlockStep() {
        TextWorld m = TextWorld.of(0,
                new String[]{"#####", "#####"},
                new String[]{"S.###", "#####"},   // y=1 walked here
                new String[]{"...G.", "....."},   // y=2 the goal, one step up
                new String[]{".....", "....."},
                new String[]{".....", "....."});
        Route.Result r = search(m);
        assertTrue(r.hasRoute(), r.reason());
        assertEquals(2, r.steps().get(r.steps().size() - 1).y());
    }

    @Test
    @DisplayName("an impossible destination is explained, not answered with a bare 'no'")
    void impossibleDestinationIsExplainedNotAnsweredWith() {
        TextWorld m = TextWorld.of(0,
                new String[]{"#####", "#####"},
                new String[]{"S....", "....."},
                new String[]{".....", "....."},
                new String[]{".....", "....."});
        Route.Result r = Route.search(m, m.exitPoint(), new Route.Point(3, 0, 0));
        assertFalse(r.hasRoute());
        assertTrue(r.reason().contains("is not a spot where one can stand"),
                r.reason());
    }

    @Test
    @DisplayName("the search budget is respected and reported")
    void searchBudgetIsRespectedAndReported() {
        TextWorld m = TextWorld.of(0,
                new String[]{"#####", "#####", "#####"},
                new String[]{"..#..", "S.#.G", "..#.."},
                new String[]{"..#..", "..#..", "..#.."},
                new String[]{".....", ".....", "....."},
                new String[]{".....", ".....", "....."});
        Route.Result r = Route.search(m, m.exitPoint(), m.goal(),
                new Route.Options(3, 3));
        assertFalse(r.hasRoute());
        assertTrue(r.reason().contains("I gave up after looking at"), r.reason());
    }

    @Test
    @DisplayName("already at the destination, no stroll is invented")
    void alreadyAtDestinationNoStrollIsInvented() {
        TextWorld m = TextWorld.of(0,
                new String[]{"###", "###"},
                new String[]{"S..", "..."},
                new String[]{"...", "..."},
                new String[]{"...", "..."});
        Route.Result r = Route.search(m, m.exitPoint(), m.exitPoint());
        assertTrue(r.hasRoute());
        assertEquals(List.of(m.exitPoint()), r.steps());
        assertEquals("I am already there", r.reason());
    }

    // --- building to arrive --------------------------------------------------

    @Test
    @DisplayName("without permission to build, a gap is a wall")
    void withoutPermissionToBuildGapIsWall() {
        // A two-wide ditch between S and G, with no floor to cross on.
        TextWorld m = TextWorld.of(0,
                new String[]{"#######", "#######"},   // y=0 bottom of the pit
                new String[]{"#.....#", "#######"},
                new String[]{"#.....#", "#######"},
                new String[]{"#.....#", "#######"},
                new String[]{"##...##", "#######"},   // y=4 pillars under S and G
                new String[]{"#S...G#", "#######"},   // y=5 walked here; x=2,3,4 empty
                new String[]{"#.....#", "#######"},
                new String[]{"#.....#", "#######"});
        Route.Result r = Route.search(m, m.exitPoint(), m.goal());
        assertFalse(r.hasRoute(), "without building it should not be able to cross");
        assertTrue(r.reason().contains("there is no route"), r.reason());
    }

    @Test
    @DisplayName("with permission, it bridges the gap")
    void withPermissionItBridgesGap() {
        TextWorld m = TextWorld.of(0,
                new String[]{"#######", "#######"},   // y=0 bottom of the pit
                new String[]{"#.....#", "#######"},
                new String[]{"#.....#", "#######"},
                new String[]{"#.....#", "#######"},
                new String[]{"##...##", "#######"},   // y=4 pillars under S and G
                new String[]{"#S...G#", "#######"},   // y=5 walked here; x=2,3,4 empty
                new String[]{"#.....#", "#######"},
                new String[]{"#.....#", "#######"});
        Route.Result r = Route.search(m, m.exitPoint(), m.goal(),
                new Route.Options(3, 20_000, true));
        assertTrue(r.hasRoute(), r.reason());
        // It crosses through the gap, at the same height: that is bridging.
        assertTrue(r.steps().stream().anyMatch(
                        q -> q.x() == 3 && q.y() == m.exitPoint().y()),
                "it did not bridge over the ditch; route: " + r.steps());
    }

    @Test
    @DisplayName("even able to build, it prefers going around if there is a way")
    void evenAbleToBuildItPrefersGoing() {
        // There is a ditch to the east, but also a detour to the south on foot.
        TextWorld m = TextWorld.of(0,
                new String[]{"######", "######", "######"},
                new String[]{"#S##G#", "#....#", "######"},   // z=1: free corridor
                new String[]{"#....#", "#....#", "######"},
                new String[]{"#....#", "#....#", "######"});
        Route.Result r = Route.search(m, m.exitPoint(), m.goal(),
                new Route.Options(3, 20_000, true));
        assertTrue(r.hasRoute(), r.reason());
        assertTrue(r.steps().stream().anyMatch(q -> q.z() == 1),
                "it should have gone around through the corridor instead of building; route: "
                + r.steps());
    }

    @Test
    @DisplayName("with permission, it builds a tower to climb what it cannot reach")
    void withPermissionItBuildsTowerToClimb() {
        // G is three blocks above, in a column without steps.
        TextWorld m = TextWorld.of(0,
                new String[]{"###", "###"},
                new String[]{"#S#", "###"},
                new String[]{"#.#", "###"},
                new String[]{"#.#", "###"},
                new String[]{"#G#", "###"},
                new String[]{"#.#", "###"},
                new String[]{"#.#", "###"});
        assertFalse(Route.search(m, m.exitPoint(), m.goal()).hasRoute(),
                "without building it should not be able to climb");

        Route.Result r = Route.search(m, m.exitPoint(), m.goal(),
                new Route.Options(3, 20_000, true));
        assertTrue(r.hasRoute(), r.reason());
        assertEquals(m.goal(), r.steps().get(r.steps().size() - 1));
        // It went up one at a time, in the same column.
        for (Route.Point q : r.steps()) {
            assertEquals(m.exitPoint().x(), q.x(), "it left the column: " + q);
        }
    }

    // --- partial routes: running out of budget is no longer coming back empty-handed
    // (the first idea ported from Baritone) --------------------

    /** A straight 40-block corridor, to exhaust small budgets. */
    private static TextWorld corridor() {
        String floor = "#".repeat(42);
        String air = "S" + ".".repeat(40) + "G";
        String emptyOne = ".".repeat(42);
        return TextWorld.of(0,
                new String[]{floor},
                new String[]{air},
                new String[]{emptyOne},
                new String[]{emptyOne});
    }

    @Test
    @DisplayName("without budget to arrive, it gives a segment that GETS CLOSER")
    void withoutBudgetToArriveItGivesSegment() {
        TextWorld m = corridor();
        Route.Result r = Route.search(m, m.exitPoint(), m.goal(),
                new Route.Options(3, 25, false, true));
        assertTrue(r.hasRoute(), r.reason());
        assertTrue(r.isPartial(), "should be declared partial: " + r.reason());
        Route.Point end = r.steps().get(r.steps().size() - 1);
        double before = blueprint(m.exitPoint(), m.goal());
        double after = blueprint(end, m.goal());
        assertTrue(after < before - 1,
                "the segment does not get closer: from " + before + " to " + after);
        assertTrue(end.x() - m.exitPoint().x() >= 5,
                "it advanced less than 5 blocks: " + end);
    }

    /** Horizontal distance, which is what the path finder measures. */
    private static double blueprint(Route.Point a, Route.Point b) {
        double dx = a.x() - b.x(), dz = a.z() - b.z();
        return Math.sqrt(dx * dx + dz * dz);
    }

    @Test
    @DisplayName("without asking for partials, the same honest failure as always")
    void withoutAskingForPartialsSameHonestFailure() {
        TextWorld m = corridor();
        Route.Result r = Route.search(m, m.exitPoint(), m.goal(),
                new Route.Options(3, 25, false, false));
        assertFalse(r.hasRoute(), "the partial has to be opt-in");
        assertTrue(r.reason().contains("I gave up"), r.reason());
    }

    @Test
    @DisplayName("walled in gives NO partial: getting closer to a wall is not advancing")
    void walledInGivesNoPartialGettingCloser() {
        // The same closed wall as always, now ASKING for partial routes: the queue
        // empties (everything reachable was explored) and the answer must still be the
        // plain "no". The Hunter's discarding of unreachable prey depends on that "no" to
        // count and give up.
        TextWorld m = TextWorld.of(0,
                new String[]{"#####", "#####", "#####"},
                new String[]{"..#..", "S.#.G", "..#.."},
                new String[]{"..#..", "..#..", "..#.."},
                new String[]{".....", ".....", "....."},
                new String[]{".....", ".....", "....."});
        Route.Result r = Route.search(m, m.exitPoint(), m.goal(),
                new Route.Options(3, 20_000, false, true));
        assertFalse(r.hasRoute(), "walled in cannot give a segment");
        assertTrue(r.reason().contains("there is no route"), r.reason());
    }

    // --- goals that are not an exact tile (Baritone's second idea) ----------

    @Test
    @DisplayName("goal 'near': reaching the ring is enough, it picks the tile")
    void goalNearReachingRingIsEnoughIt() {
        TextWorld m = TextWorld.of(0,
                new String[]{"########", "########", "########"},
                new String[]{"........", "S.....G.", "........"},
                new String[]{"........", "........", "........"},
                new String[]{"........", "........", "........"});
        Route.Result r = Route.search(m, m.exitPoint(),
                Route.Meta.near(m.goal(), 2.0), Route.Options.byDefault());
        assertTrue(r.hasRoute(), r.reason());
        Route.Point end = r.steps().get(r.steps().size() - 1);
        assertTrue(blueprint(end, m.goal()) <= 2.0,
                "it ended outside the ring: " + end);
        assertTrue(blueprint(end, m.goal()) > 0,
                "it went to the exact tile needlessly");
    }

    @Test
    @DisplayName("goal 'only X and Z': the right column, at any height")
    void goalOnlyXAndZRightColumn() {
        // G is one floor up, with a step: the goal asks for its column, not its height;
        // arriving below or above in that column counts all the same.
        TextWorld m = TextWorld.of(0,
                new String[]{"#####", "#####"},
                new String[]{"S..##", "....."},
                new String[]{"...G#", "....."},
                new String[]{".....", "....."},
                new String[]{".....", "....."});
        Route.Result r = Route.search(m, m.exitPoint(),
                Route.Meta.onlyXZ(m.goal().x(), m.goal().z()),
                Route.Options.byDefault());
        assertTrue(r.hasRoute(), r.reason());
        Route.Point end = r.steps().get(r.steps().size() - 1);
        assertEquals(m.goal().x(), end.x(), "wrong column: " + end);
        assertEquals(m.goal().z(), end.z(), "wrong column: " + end);
    }

    @Test
    @DisplayName("anti-dithering: recently stepped tiles are avoided if there is a twin")
    void antiDitheringRecentlySteppedTilesAreAvoided() {
        // An open field of three rows: the straight path goes through the middle. If the
        // middle gets more expensive (just stepped on), the row beside it (which costs
        // almost the same) must win.
        TextWorld m = TextWorld.of(0,
                new String[]{"######", "######", "######"},
                new String[]{"......", "S....G", "......"},
                new String[]{"......", "......", "......"},
                new String[]{"......", "......", "......"});
        Route.Result direct = Route.search(m, m.exitPoint(), m.goal());
        assertTrue(direct.hasRoute(), direct.reason());

        java.util.Set<Route.Point> footsteps = new java.util.HashSet<>(
                direct.steps());
        footsteps.remove(m.exitPoint());
        footsteps.remove(m.goal());
        Route.Result r = Route.search(m, m.exitPoint(), m.goal(),
                Route.Options.byDefault().avoiding(footsteps));
        assertTrue(r.hasRoute(), r.reason());
        // The penalty is SOFT on purpose: re-entering a tile at the final junction is
        // cheaper than going around it, and that is fine; what must not happen is
        // repeating the whole route.
        long resteps = r.steps().stream().filter(footsteps::contains).count();
        assertTrue(resteps <= 1, "restepped " + resteps + " tiles of the "
                + "previous route; route: " + r.steps());
        assertFalse(r.steps().equals(direct.steps()),
                "same exact route despite the penalty");
    }

    // --- breaking to advance (the break_to_advance toggle) ------------------

    @Test
    @DisplayName("with the toggle, it tunnels through the breakable when there is no way")
    void withToggleItTunnelsThroughBreakableWhen() {
        // The same closed wall as always, but made of whitelisted rock.
        TextWorld m = TextWorld.of(0,
                new String[]{"#####", "#####", "#####"},
                new String[]{"..r..", "S.r.G", "..r.."},
                new String[]{"..r..", "..r..", "..r.."},
                new String[]{".....", ".....", "....."},
                new String[]{".....", ".....", "....."});
        assertFalse(Route.search(m, m.exitPoint(), m.goal()).hasRoute(),
                "without the toggle, the breakable is a wall like any other");
        Route.Result r = Route.search(m, m.exitPoint(), m.goal(),
                Route.Options.byDefault().breaking(true));
        assertTrue(r.hasRoute(), r.reason());
        assertEquals(m.goal(), r.steps().get(r.steps().size() - 1));
    }

    @Test
    @DisplayName("even tunnelling, it prefers going around if there is a walking way")
    void evenTunnellingItPrefersGoingAroundIf() {
        // The same breakable wall but with a gap to the north: digging costs more than a
        // few extra steps, so the gap wins.
        TextWorld m = TextWorld.of(0,
                new String[]{"#######", "#######", "#######", "#######"},
                new String[]{".......", "..r....", "S.r..G.", "..r...."},
                new String[]{".......", "..r....", "..r....", "..r...."},
                new String[]{".......", ".......", ".......", "......."},
                new String[]{".......", ".......", ".......", "......."});
        Route.Result r = Route.search(m, m.exitPoint(), m.goal(),
                Route.Options.byDefault().breaking(true));
        assertTrue(r.hasRoute(), r.reason());
        assertTrue(r.steps().stream().anyMatch(q -> q.z() == 0),
                "it tunnelled instead of going around through the gap; route: " + r.steps());
    }

    // --- water, which used to cost the same as land ------------------------

    @Test
    @DisplayName("it prefers the dry detour to the shortcut under water")
    void itPrefersDryDetourToShortcutUnder() {
        // A bot drowned exactly like this: the straight path crossed a lake, a swimming
        // step cost the same as a walking one, and so the shortcut always won. With a
        // shore nearby, the shore wins now.
        TextWorld m = TextWorld.of(0,
                new String[]{"#####", "#####", "#####"},
                new String[]{"S~~~G", ".....", "....."},   // y=1: the ford
                new String[]{".~~~.", ".....", "....."});  // y=2: water above
        Route.Result r = Route.search(m, m.exitPoint(), m.goal());
        assertTrue(r.hasRoute(), r.reason());
        assertTrue(r.steps().stream().noneMatch(
                        q -> m.water(q.x(), q.y(), q.z())),
                "it crossed diving with a shore available; route: " + r.steps());
    }

    @Test
    @DisplayName("if there is no shore, it swims across anyway: it is costly, not forbidden")
    void ifThereIsNoShoreItSwims() {
        // The other half of the rule, and the one that keeps the fix from becoming a
        // wall: water makes things more expensive, it never closes the way. Lava does
        // close it; water does not, because you can get out of water.
        TextWorld m = TextWorld.of(0,
                new String[]{"#####", "#####"},
                new String[]{"S~~~G", "#####"},
                new String[]{".~~~.", "#####"});
        assertTrue(Route.search(m, m.exitPoint(), m.goal()).hasRoute(),
                "with no detour possible it should still swim across");
    }

    // --- lava, which used to be water ---------------------------------------

    @Test
    @DisplayName("lava is not water: one neither walks on it nor floats in it")
    void lavaIsNotWaterOneNeitherWalks() {
        TextWorld m = TextWorld.of(0,
                new String[]{"#####"},
                new String[]{"..L.."},
                new String[]{"....."});
        assertFalse(m.canStand(2, 1, 0),
                "standing INSIDE the lava, as if floating");
        assertTrue(m.canStand(1, 1, 0), "next to it one can stand");
    }

    @Test
    @DisplayName("a lava pool on the way is a wall, not a ford")
    void lavaPoolOnWayIsWallNot() {
        // It used to cross calmly: the client took lava for water, and water can be
        // walked through. Now, with nowhere to go around, there is no route; saying "I
        // cannot get there" is infinitely better than arriving melted.
        TextWorld m = TextWorld.of(0,
                new String[]{"#####"},
                new String[]{"S.LLG"},
                new String[]{"....."});
        assertFalse(Route.search(m, m.exitPoint(), m.goal()).hasRoute(),
                "it walked across the lava");
    }

    @Test
    @DisplayName("OVER the lava if bridged")
    void overLavaIfBridged() {
        // The canyon with lava at the bottom: the bridge step goes over it, and the block
        // goes under the feet, not into the fluid.
        TextWorld m = TextWorld.of(0,
                new String[]{"#####"},
                new String[]{"##LL#"},
                new String[]{"S...G"},
                new String[]{"....."});
        Route.Result r = Route.search(m, m.exitPoint(), m.goal(),
                new Route.Options(3, 20_000, true));
        assertTrue(r.hasRoute(), r.reason());
        assertTrue(r.steps().stream().noneMatch(
                        q -> m.lava(q.x(), q.y(), q.z())),
                "the route goes through the lava; route: " + r.steps());
    }

    // --- doors --------------------------------------------------------------

    @Test
    @DisplayName("a closed door is opened and crossed, it is not a wall")
    void closedDoorIsOpenedAndCrossedIt() {
        // A corridor with a door: the feet tile and the head tile are both door, as in
        // the game (a door is two blocks).
        TextWorld m = TextWorld.of(0,
                new String[]{"#####"},
                new String[]{"S.P.G"},
                new String[]{"..P.."},
                new String[]{"....."});
        Route.Result r = Route.search(m, m.exitPoint(), m.goal());
        assertTrue(r.hasRoute(), r.reason());
        assertTrue(r.steps().stream().anyMatch(q -> q.x() == 2 && q.y() == 1),
                "it did not go through the door; route: " + r.steps());
    }

    @Test
    @DisplayName("with a gap next to it, it prefers not to open the door")
    void withGapNextToItItPrefers() {
        // Opening costs, so a short detour wins. The same economy as tunnelling: being
        // able to do it is not wanting to.
        TextWorld m = TextWorld.of(0,
                new String[]{"#####", "#####"},
                new String[]{"S.P.G", "....."},
                new String[]{"..P..", "....."},
                new String[]{".....", "....."});
        Route.Result r = Route.search(m, m.exitPoint(), m.goal());
        assertTrue(r.hasRoute(), r.reason());
        assertTrue(r.steps().stream().anyMatch(q -> q.z() == 1),
                "it opened the door when it could go around; route: " + r.steps());
    }
}
