package masurium.common;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Finds a path from one point to another. A* over the tiles where one can stand.
 *
 * <p><b>The algorithm is the easy part.</b> Behaviour lives in the cost table below, and
 * there is historical proof of it: with a previous navigation engine the bot threw itself
 * off cliffs, and it was not a search bug. Baritone charged <i>a cost of 1 for any fall
 * regardless of height</i>, so for the algorithm a ten-block drop was ten times cheaper
 * than the ten steps of a staircase. It was not misbehaving: it behaved optimally
 * according to what it was told things cost.
 *
 * <p>It knows nothing about Minecraft: it only asks the {@link World}. That is why it can
 * be tested against a map drawn with text.
 */
public final class Route {

    private Route() {}

    public record Point(int x, int y, int z) {
        /**
         * Estimate of what is left. **It only counts the horizontal distance**, and that
         * is not an oversight.
         * A* only returns the optimal route if the estimate never exceeds the real cost.
         * Going down is almost free (a three-block fall costs a step), so adding the
         * height inflates it and makes A* stop early and return a worse path.
         * A mutation test found it: with the fall cost set to zero, the path finder STILL
         * did not jump off the cliff, when it should have. That was not virtue, it was
         * this.
         */
        double heuristic(Point o) {
            double dx = x - o.x, dz = z - o.z;
            return Math.sqrt(dx * dx + dz * dz);
        }
    }

    /**
     * Where to arrive, as a CONDITION and not as a tile: the second idea ported from
     * Baritone (its Goal* classes). An exact tile is just one case; "within 2 of the pig"
     * or "this X and Z, any height" are equally legitimate goals, and picking the tile by
     * hand was the source of null standing tiles and of chasing feet instead of mobs.
     */
    public interface Meta {
        /** Does this tile already satisfy it? */
        boolean isGoal(int x, int y, int z);
        /**
         * Estimate of the cost LEFT, in the same units as the table (ticks). Never over
         * the real one, or A* cuts wrong.
         */
        double heuristic(int x, int y, int z);

        /** The usual exact tile. */
        static Meta exact(Point p) {
            return new Meta() {
                public boolean isGoal(int x, int y, int z) {
                    return x == p.x() && y == p.y() && z == p.z();
                }
                public double heuristic(int x, int y, int z) {
                    double dx = x - p.x(), dz = z - p.z();
                    return Math.sqrt(dx * dx + dz * dz) * WALK
                            + height(y, p.y(), 0);
                }
                public String toString() { return "exact " + p; }
            };
        }

        /**
         * Within {@code radius} of the point (horizontally; Y weighs half, as it does in
         * practice: two floors away is not two blocks of walking). For chasing: any tile
         * of the ring will do, the path finder chooses.
         */
        static Meta near(Point p, double radius) {
            double radiusSq = radius * radius;
            return new Meta() {
                public boolean isGoal(int x, int y, int z) {
                    double dx = x - p.x(), dz = z - p.z();
                    double dy = (y - p.y()) * 0.5;
                    return dx * dx + dz * dz + dy * dy <= radiusSq;
                }
                public double heuristic(int x, int y, int z) {
                    double dx = x - p.x(), dz = z - p.z();
                    double missing = Math.sqrt(dx * dx + dz * dz) - radius;
                    // Y weighs half in isGoal, so the ring admits twice the radius in
                    // height before charging anything.
                    return Math.max(0, missing) * WALK
                            + height(y, p.y(), 2 * radius);
                }
                public String toString() {
                    return String.format("%.1f from %s", radius, p);
                }
            };
        }

        /**
         * This column, at any height: "X and Z first, Y up close", as a first-class goal.
         */
        static Meta onlyXZ(int mx, int mz) {
            return new Meta() {
                public boolean isGoal(int x, int y, int z) {
                    return x == mx && z == mz;
                }
                public double heuristic(int x, int y, int z) {
                    double dx = x - mx, dz = z - mz;
                    return Math.sqrt(dx * dx + dz * dz) * WALK;
                }
                public String toString() {
                    return "column " + mx + "," + mz;
                }
            };
        }
    }

    /**
     * @param steps  the route, or null if there is none
     * @param reason always present: why it came out like this. Never a bare "no".
     * @param looked tiles explored, to know whether it gave up because of a wall or of
     *               the budget
     * @param partial true = it does NOT reach the destination: it is a segment that GETS
     *                CLOSER. Walk it and search again from its end.
     */
    public record Result(List<Point> steps, String reason, int looked,
                            boolean partial) {
        /** The usual one: a complete result, or a bare failure. */
        public Result(List<Point> steps, String reason, int looked) {
            this(steps, reason, looked, false);
        }
        public boolean hasRoute() { return steps != null; }
        public boolean isPartial() { return partial; }
    }

    // --- the cost table, which is what really decides --------------------------
    // Costs are GAME TICKS, not "steps": the third idea ported from Baritone (its
    // ActionCosts). With real time, "detour or drop?" is decided with physics and not
    // with magic numbers. The PROPORTIONS of the old table were kept on purpose (every
    // behaviour test still passes untouched): this recalibrates the yardstick, not the
    // judgment.

    /** Walking one block: 20 ticks / the game's 4.317 m/s. */
    private static final double WALK = 4.633;
    private static final double DIAGONAL = WALK * 1.414;
    /** Climbing a step: the jump adds ~half a walk (2.3 ticks). */
    private static final double CLIMB_EXTRA = 2.3;

    // --- height in the heuristic ---------------------------------------------------
    // Goals used to estimate only in XZ: a tile 40 blocks BELOW the destination, in its
    // column, was worth h=0, "you have arrived". With that, a segment that only CLIMBS
    // improved nothing, bestSegment() rejected it, and the Traveler counted "no progress"
    // until giving up ("I got stuck" with high destinations, and a bot building towers on
    // a peak because walking away to find the ramp was "moving away from the goal").
    // Baritone (GoalBlock + GoalYLevel) adds to the estimate one jump per block of climb
    // and half a fall per block of descent; the idea is ported here with our costs.
    // As in Baritone, the sum is not strictly admissible: a step that climbs also
    // advances in XZ and is counted twice. Accepted on purpose: better to quickly find a
    // slightly longer route than to find none within the deadline.

    /** Every block of climb requires at least one step. */
    private static final double CLIMB_PER_BLOCK = WALK + CLIMB_EXTRA;
    /**
     * A step down costs the same as walking, so the strict minimum is zero; a little is
     * charged so that going down also counts as getting closer (Baritone charges half a
     * fall).
     */
    private static final double DESCEND_PER_BLOCK = 2.0;

    /**
     * The minimum cost of covering the height up to {@code goalY}, with {@code tolerance}
     * blocks the goal admits for free.
     */
    private static double height(int y, int goalY, double tolerance) {
        double dy = Math.abs(y - goalY) - tolerance;
        if (dy <= 0) return 0;
        return y < goalY ? dy * CLIMB_PER_BLOCK : dy * DESCEND_PER_BLOCK;
    }
    /**
     * What is paid for EACH block of fall beyond the first.
     * This is the number that prevents the cliff bug. With 0 here, A* prefers going over
     * the edge to walking around, because it arrives just the same in fewer steps. In
     * ticks: more than the pure physical fall, on purpose: the extra is respect for life,
     * which physics does not charge.
     */
    private static final double FALL_PER_BLOCK = 14.0;

    /**
     * How far it peeks when looking for a way down. Beyond the fall limit it only serves
     * to find water, which makes it free.
     */
    private static final int LOOK_DOWN_LIMIT = 24;

    /**
     * Search budget. Without a cap, a walled-in destination explores half the world
     * before giving up.
     */
    private static final int NODES_MAX = 20_000;

    // --- partial routes (Baritone's central idea, rewritten) ----------------------
    // A search that runs out of budget need not come back empty-handed: the segment that
    // gets CLOSEST is walked, and the search runs again from its end. Repeated, it gets
    // where a single search could not. Baritone (LGPL, cabaletta/baritone) calls it
    // bestSoFar; the idea is ported here, not the code.

    /**
     * The "how much getting closer weighs against how much it cost" watched at once.
     * Small = a balanced segment (almost optimal); large = anything, as long as it ends
     * up CLOSE. Tried in this order.
     */
    private static final double[] COEFFICIENTS = {1.5, 3, 10};
    /** A segment advancing less than this (blocks) is not worth the walk. */
    private static final double MIN_PROGRESS = 5.0;
    /**
     * And it has to really get closer: without a minimum improvement, the caller would
     * repeat the same segment forever. One block, in ticks.
     */
    private static final double APPROACH_MINIMUM = WALK;

    /**
     * How much more expensive a just-stepped tile becomes, so two equally good routes do
     * not alternate on every replan (Baritone's Favoring, its issue #18). Soft on
     * purpose: if the old path is clearly better, it is repeated anyway.
     */
    private static final double RESTEP_PENALTY = 1.25;
    /** The clock is not free: it is checked every so many tiles. */
    private static final int CHECK_CLOCK_EVERY = 64;

    /**
     * Placing a block to cross a gap, and placing it under the feet to climb.
     * Expensive on purpose: they spend material, take time, and above all **change other
     * people's world**. With these numbers A* only builds when there is no alternative on
     * foot, just as it only goes over an edge when there is no staircase.
     */
    private static final double BRIDGE = 23.0;
    private static final double TOWER = 28.0;

    /**
     * Digging ONE block to get through (break_to_advance toggle). More expensive than any
     * reasonable detour on purpose: tunnelling destroys other people's world, so it only
     * wins when there is no path on foot; the same treatment as bridge and tower, a bit
     * stricter.
     */
    private static final double BREAK = 40.0;
    /**
     * Opening a door, crossing and closing it. Cheap compared to going around a whole
     * house, but not free: it costs two clicks and slows the pace.
     */
    private static final double DOOR = 12.0;

    /**
     * Getting into water. Swimming goes at half the walking speed, so a swimming step
     * costs like two on foot. So far it is only slowness.
     */
    private static final double SWIM = WALK;

    /**
     * And with the HEAD under too. This is no longer slowness: it is the only place in
     * the world where one drowns, with fifteen seconds of air and no warning.
     *
     * <p>A swimming step used to cost exactly the same as one over land, so the shortcut
     * through water ALWAYS won. A bot drowned crossing a lake on its way to a place a
     * thousand five hundred blocks away.
     *
     * <p>Calibrated on purpose BELOW {@link #BRIDGE}: swimming across is still better
     * than building a bridge over the lake (building changes other people's world), but a
     * detour over land of up to about four blocks per block of diving already wins.
     * Swimming with the head out does not pay this: that kills nobody.
     */
    private static final double DIVE = 12.0;
    /**
     * Entering (feet or head) a berry bush, fire, powder snow or wither rose: it hurts
     * and slows. More expensive than breaking a block: only passed through when there is
     * no other way, such as getting out of one.
     */
    private static final double DANGER = 60.0;

    /**
     * @param maxFall how many blocks of fall are accepted. The damage is {@code (blocks -
     *                3)} half hearts, so the caller decides this, according to its
     *                health.
     */
    /**
     * @param deadlineMs cap on search MILLISECONDS (0 = no deadline). Searches run on the
     *                   game thread: one that drags on steals the tick from everything
     *                   else. With partial routes requested, running out of time is not
     *                   failing: it is "walk what I found and keep searching".
     * @param avoid  just-stepped tiles, made {@value #RESTEP_PENALTY}x more expensive so
     *               it does not oscillate between two twin routes when replanning. Null =
     *               no penalty.
     */
    public record Options(int maxFall, int maxNodes,
                           boolean canBuild, boolean partials,
                           long deadlineMs, java.util.Set<Point> avoid,
                           boolean canBreak) {
        public static Options byDefault() {
            return new Options(3, NODES_MAX, false, false, 0, null, false);
        }
        /**
         * Without building, which is the normal case: the bot does not redecorate the
         * house.
         */
        public Options(int maxFall, int maxNodes) {
            this(maxFall, maxNodes, false, false, 0, null, false);
        }
        public Options(int maxFall, int maxNodes,
                        boolean canBuild) {
            this(maxFall, maxNodes, canBuild, false, 0, null,
                    false);
        }
        /**
         * Partial routes are OPT-IN on purpose: for a "/go_to" a half segment would lie
         * to whoever asked to arrive; for a chase it is exactly what is needed. Each
         * caller decides.
         */
        public Options(int maxFall, int maxNodes,
                        boolean canBuild, boolean partials) {
            this(maxFall, maxNodes, canBuild, partials, 0,
                    null, false);
        }
        public Options(int maxFall, int maxNodes,
                        boolean canBuild, boolean partials,
                        long deadlineMs, java.util.Set<Point> avoid) {
            this(maxFall, maxNodes, canBuild, partials,
                    deadlineMs, avoid, false);
        }
        public Options withDeadline(long ms) {
            return new Options(maxFall, maxNodes, canBuild,
                    partials, ms, avoid, canBreak);
        }
        public Options avoiding(java.util.Set<Point> footsteps) {
            return new Options(maxFall, maxNodes, canBuild,
                    partials, deadlineMs, footsteps, canBreak);
        }
        /** The break_to_advance toggle, already read by the caller. */
        public Options breaking(boolean yes) {
            return new Options(maxFall, maxNodes, canBuild,
                    partials, deadlineMs, avoid, yes);
        }
    }

    public static Result search(World m, Point from, Point until) {
        return search(m, from, until, Options.byDefault());
    }

    /**
     * The historical signature: an exact tile, with its courtesy of warning BEFOREHAND if
     * the destination is not even walkable.
     */
    public static Result search(World m, Point from, Point until, Options op) {
        // Able to build, the destination only needs to be EMPTY: the floor gets placed.
        // Requiring a floor would reject exactly the places reached by building, which is
        // what building is for.
        boolean goalValid = m.canStand(until.x, until.y, until.z)
                || (op.canBuild()
                    && !m.solid(until.x, until.y, until.z)
                    && !m.solid(until.x, until.y + 1, until.z));
        if (!goalValid) {
            return new Result(null, "the destination is not a spot where one can "
                    + "stand (is it inside a block, or in the air?)", 0);
        }
        return search(m, from, Meta.exact(until), op);
    }

    public static Result search(World m, Point from, Meta goal, Options op) {
        // Fail with a reason: "one cannot stand there" and "there is no route" are fixed
        // in different ways, so they cannot give the same error.
        if (!m.canStand(from.x, from.y, from.z)) {
            return new Result(null, "I cannot start: where I am is not a "
                    + "spot where one can stand", 0);
        }
        if (goal.isGoal(from.x, from.y, from.z)) {
            return new Result(List.of(from), "I am already there", 0);
        }

        Map<Point, Point> comingFrom = new HashMap<>();
        Map<Point, Double> realCost = new HashMap<>();
        PriorityQueue<Point> queue = new PriorityQueue<>(
                (a, b) -> Double.compare(
                        realCost.getOrDefault(a, Double.MAX_VALUE)
                                + goal.heuristic(a.x, a.y, a.z),
                        realCost.getOrDefault(b, Double.MAX_VALUE)
                                + goal.heuristic(b.x, b.y, b.z)));

        realCost.put(from, 0.0);
        queue.add(from);
        int looked = 0;
        long deadlineUntil = op.deadlineMs() > 0
                ? System.currentTimeMillis() + op.deadlineMs() : 0;

        // With partial routes, the best point seen according to each coefficient.
        Point[] bestPartial = null;
        double[] bestMetric = null;
        if (op.partials()) {
            bestPartial = new Point[COEFFICIENTS.length];
            double h0 = goal.heuristic(from.x, from.y, from.z);
            bestMetric = new double[COEFFICIENTS.length];
            for (int i = 0; i < COEFFICIENTS.length; i++) {
                bestPartial[i] = from;
                bestMetric[i] = h0;
            }
        }

        while (!queue.isEmpty()) {
            Point current = queue.poll();
            if (goal.isGoal(current.x, current.y, current.z)) {
                return new Result(rebuild(comingFrom, current),
                        "route found", looked);
            }
            // The clock is checked every so many tiles (checking it is not free) and
            // treated JUST like the node budget: with partial routes, running out is
            // "walk what I found".
            boolean outOfTime = deadlineUntil > 0
                    && (looked & (CHECK_CLOCK_EVERY - 1)) == 0
                    && System.currentTimeMillis() > deadlineUntil;
            if (++looked > op.maxNodes() || outOfTime) {
                Result segment = bestSegment(comingFrom, from, goal,
                        bestPartial, looked);
                if (segment != null) return segment;
                return new Result(null, String.format(
                        "I gave up after looking at %d tiles without finding a route"
                        + "%s; it may be walled off or too far",
                        looked, outOfTime ? " (time ran out)" : ""),
                        looked);
            }

            for (Step step : neighbors(m, current, op)) {
                double cost = step.cost();
                // The anti-dithering penalty: re-stepping the route from a moment ago
                // costs more, so two twin paths do not alternate on every replan.
                if (op.avoid() != null
                        && op.avoid().contains(step.destination())) {
                    cost *= RESTEP_PENALTY;
                }
                double latest = realCost.get(current) + cost;
                if (latest < realCost.getOrDefault(step.destination(), Double.MAX_VALUE)) {
                    realCost.put(step.destination(), latest);
                    comingFrom.put(step.destination(), current);
                    queue.add(step.destination());
                    if (bestPartial != null) {
                        double h = goal.heuristic(step.destination().x,
                                step.destination().y, step.destination().z);
                        for (int i = 0; i < COEFFICIENTS.length; i++) {
                            if (h + latest / COEFFICIENTS[i]
                                    < bestMetric[i] - 0.01) {
                                bestMetric[i] = h + latest / COEFFICIENTS[i];
                                bestPartial[i] = step.destination();
                            }
                        }
                    }
                }
            }
        }
        // The queue emptied: EVERYTHING reachable was explored and the destination did
        // not show up. NO partial is given here on purpose: being walled in is reported
        // as such, because the discarding of unreachable prey (Hunter/Archer) depends on
        // this "no" to work. Partial routes are for running out of budget, not out of
        // world.
        return new Result(null, String.format(
                "there is no route; I looked at the %d reachable tiles", looked),
                looked);
    }

    /**
     * The best partial segment worth taking, or null. Walked in the order of {@link
     * #COEFFICIENTS}: first the balanced one, which is the closest to a real optimum. The
     * two conditions are the anti-loop lock: advance {@value #MIN_PROGRESS} blocks and
     * get {@value #APPROACH_MINIMUM} of cost closer at least; a segment that improves
     * nothing is rejected so the caller's stuck counter can count and discard.
     */
    private static Result bestSegment(Map<Point, Point> comingFrom, Point from,
            Meta goal, Point[] candidates, int looked) {
        if (candidates == null) return null;
        double hFrom = goal.heuristic(from.x, from.y, from.z);
        for (Point c : candidates) {
            if (c.equals(from)) continue;
            double dx = c.x() - from.x(), dy = c.y() - from.y(),
                    dz = c.z() - from.z();
            double progress = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double h = goal.heuristic(c.x(), c.y(), c.z());
            if (progress < MIN_PROGRESS || hFrom - h < APPROACH_MINIMUM) {
                continue;
            }
            return new Result(rebuild(comingFrom, c), String.format(
                    "I did not arrive within the budget, but this segment leaves me "
                    + "%.0f blocks from the destination", h / WALK), looked, true);
        }
        return null;
    }

    // --- the movement model ---------------------------------------------------------

    private record Step(Point destination, double cost) {}

    private static final int[][] SIDES = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    private static List<Step> neighbors(World m, Point p, Options op) {
        List<Step> steps = new ArrayList<>(12);
        for (int[] d : SIDES) {
            int dx = d[0], dz = d[1];
            boolean diagonal = dx != 0 && dz != 0;

            // Diagonally both sides must be passable too, or the player gets caught on
            // the corner.
            if (diagonal && !(free(m, p.x + dx, p.y, p.z)
                              && free(m, p.x, p.y, p.z + dz))) {
                continue;
            }
            double base = diagonal ? DIAGONAL : WALK;
            int x = p.x + dx, z = p.z + dz;

            // 1. same level. If there is a closed door, opening it is paid for: it can be
            //    passed, but not for free.
            if (m.canStand(x, p.y, z)) {
                double extra = m.door(x, p.y, z) || m.door(x, p.y + 1, z)
                        ? DOOR : 0;
                steps.add(new Step(new Point(x, p.y, z),
                        base + extra + getWet(m, x, p.y, z)));
                continue;
            }
            // 2. climb a step: room above the current head is needed
            if (m.canStand(x, p.y + 1, z) && !m.solid(p.x, p.y + 2, p.z)) {
                steps.add(new Step(new Point(x, p.y + 1, z),
                        base + CLIMB_EXTRA + getWet(m, x, p.y + 1, z)));
                continue;
            }
            // 2b. same level but BLOCKED by blocks the whitelist allows digging
            // (break_to_advance toggle): the digging is paid per block. Only straight,
            // like the bridge, and with solid ground: the classic tunnel through dirt or
            // stone.
            if (op.canBreak() && !diagonal
                    && m.solid(x, p.y - 1, z)
                    && passable(m, x, p.y, z)
                    && passable(m, x, p.y + 1, z)
                    && (m.solid(x, p.y, z) || m.solid(x, p.y + 1, z))) {
                int toDig = (m.solid(x, p.y, z) ? 1 : 0)
                        + (m.solid(x, p.y + 1, z) ? 1 : 0);
                steps.add(new Step(new Point(x, p.y, z),
                        base + toDig * BREAK));
                continue;
            }
            // 3. bridge: the tile beside is free but has no floor. A block is placed
            //    below and it is crossed. Only straight: diagonally there is nothing to
            //    rest the block on. Bridging OVER lava is fine (that is how it is
            //    crossed), but not bridging INTO it: the block goes under the feet, not
            //    in front.
            if (op.canBuild() && !diagonal
                    && !m.solid(x, p.y, z) && !m.solid(x, p.y + 1, z)
                    && !m.solid(x, p.y - 1, z)
                    && !m.lava(x, p.y, z) && !m.lava(x, p.y + 1, z)) {
                steps.add(new Step(new Point(x, p.y, z), base + BRIDGE));
                continue;
            }
            // 4. go down: the first walkable spot below is looked for. It looks BELOW the
            //    fall limit on purpose: if there is water at the bottom, height stops
            //    mattering; water cancels damage from any height, one block is enough.
            // But first it has to be ABLE TO ENTER that column: when going down a step
            // the body first moves sideways, at the current height, and only then drops.
            // If there is a ceiling at head height (p.y+1), it bumps and never goes down.
            // The loop below only looked from p.y down, so it said yes to a ONE-block
            // gap, as if the player were one block tall.
            if (!free(m, x, p.y, z)) continue;
            for (int fall = 1; fall <= LOOK_DOWN_LIMIT; fall++) {
                int y = p.y - fall;
                if (m.solid(x, y + 1, z)) break;      // ceiling: no passing
                // Lava stops the fall dead: below it there is no landing, one falls INTO
                // it.
                if (m.lava(x, y, z)) break;
                if (!m.canStand(x, y, z)) continue;
                boolean intoWater = m.water(x, y, z);
                if (!intoWater && fall > op.maxFall()) break;  // it hurts too much
                // Falling into water is still free (that is what saves its life going
                // down), but the pool it lands in costs like any other: landing with the
                // head under is diving.
                double penalty = intoWater ? 0 : (fall - 1) * FALL_PER_BLOCK;
                steps.add(new Step(new Point(x, y, z),
                        base + penalty + getWet(m, x, y, z)));
                break;
            }
        }
        // 5. tower: climb by placing a block under its own feet. It goes outside the
        //    sides loop because it does not change column.
        if (op.canBuild() && !m.solid(p.x, p.y + 1, p.z)
                && !m.solid(p.x, p.y + 2, p.z)) {
            steps.add(new Step(new Point(p.x, p.y + 1, p.z), TOWER));
        }
        return steps;
    }

    /**
     * The EXTRA cost of entering this tile because it is wet.
     *
     * <p>With the head out it is only going slowly. With the head under (water above too)
     * it is diving, and that is paid separately: that is where one drowns. Without water
     * it costs nothing, which is how everything behaved before.
     */
    private static double getWet(World m, int x, int y, int z) {
        double dangerCost = m.dangerous(x, y, z) || m.dangerous(x, y + 1, z)
                ? DANGER : 0;
        if (!m.water(x, y, z)) return dangerCost;
        return dangerCost + (m.water(x, y + 1, z) ? SWIM + DIVE : SWIM);
    }

    /**
     * It can be passed through here. Not lava: brushing it at a corner burns just like
     * getting in.
     */
    private static boolean free(World m, int x, int y, int z) {
        // A closed door is not crossed sideways either: one enters a door head on, after
        // opening it.
        return !m.solid(x, y, z) && !m.solid(x, y + 1, z)
                && !m.lava(x, y, z) && !m.lava(x, y + 1, z)
                && !m.door(x, y, z) && !m.door(x, y + 1, z);
    }

    /**
     * Air, or something the whitelist allows digging. Never lava: digging the block that
     * holds it back is opening the door to the lava.
     */
    private static boolean passable(World m, int x, int y, int z) {
        if (m.lava(x, y, z)) return false;
        return !m.solid(x, y, z) || m.breakable(x, y, z);
    }

    private static List<Point> rebuild(Map<Point, Point> comingFrom, Point end) {
        List<Point> route = new ArrayList<>();
        for (Point p = end; p != null; p = comingFrom.get(p)) route.add(p);
        Collections.reverse(route);
        return route;
    }
}
