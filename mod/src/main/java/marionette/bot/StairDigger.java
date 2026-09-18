package marionette.bot;

import marionette.common.Logbook;
import marionette.common.Request;
import marionette.common.Route;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * Going down to an elevation by digging a zig-zag staircase.
 *
 * <p>When mining, the bot must not dig straight down: it has no way back up. A vertical
 * shaft is a trap; a staircase can be walked down and up, and the path finder understands
 * it on its own.
 *
 * <p><b>Each step is one block forward and one down</b>, and to fit, THREE tiles of the
 * destination column must be opened: the step's feet, its head, and the tile at the
 * current head's height, because the body goes sideways first and only then drops. That
 * is exactly the gap {@code Route} was missing when going down, so the staircase it
 * leaves is one the path finder knows how to climb.
 *
 * <p><b>Zig-zag</b>: {@value #SEGMENT} steps in one direction and a ninety-degree turn,
 * alternating right and left. That way it never gets more than a few tiles away from the
 * starting point and needs no landing.
 *
 * <p>The brakes are the {@link StripMiner}'s: a block with lava or water next to it is
 * never opened (here that STOPS the staircase and says where: in a half-dug shaft there
 * is no branch to cut), the step's floor is covered with scaffolding if missing, and a
 * broken pickaxe or a missing permission stops it. The {@link Torchbearer} places torches
 * when the light reaches zero.
 */
final class StairDigger {

    /** Steps per segment before turning. */
    private static final int SEGMENT = 4;
    /** Below this there is no floor worth having: the world ends at -64. */
    private static final int MIN_ELEVATION = -59;
    private static final int STUCK_TICKS = 20 * 40;

    private enum Phase { CLEAR, ADVANCE, RETURN, ALTERNATIVE, TRAVERSE }
    /** Radius in which another spot to keep going down from is looked for. */
    private static final int ALT_RADIUS = 8;
    private static final int ALT_DESCENT = 6;

    private final Walker walker;
    private final Miner miner;

    private boolean descending;
    private String outcome = "I have not gone down any staircase";
    private Phase phase = Phase.CLEAR;
    private int ticksInPhase;

    private Direction currentDir;
    private BlockPos cell;
    private int elevation;
    private int steps;
    private int segmentSteps;
    private boolean turnRight = true;
    private int attempts;
    /** Ticks left waiting for the server to confirm the floor block. */
    private int groundTicks;
    private int groundAttempts;
    /** Turns in a row without managing a step: at four, there is no way. */
    private int consecutiveTurns;
    /** Candidate spots to keep going down from, still to try. */
    private java.util.List<BlockPos> candidates;
    /**
     * Whether the RETURN in progress is a relocation: on arriving, the staircase
     * continues from there.
     */
    private boolean relocating;
    private BlockPos returnDestination;
    /**
     * The steps of THIS staircase, from the head down, to save it on arrival (see {@link
     * Staircases}). A relocation leaves a gap in the path and then it is not saved.
     */
    private final List<BlockPos> traversal = new ArrayList<>();
    private boolean traversalValid;
    private BlockPos initialHead;
    /**
     * Walking a SAVED staircase (without digging): where it has to arrive, and whether
     * going up.
     */
    private BlockPos savedEnd;
    private boolean climbing;
    /**
     * Digging UPWARDS (the requested Y is above and there is no saved staircase with its
     * foot nearby). The same zig-zag, reversed.
     */
    private boolean upward;
    private static final int MAX_ELEVATION = 120;

    StairDigger(Walker walker, Miner miner) {
        this.walker = walker;
        this.miner = miner;
    }

    /**
     * @param until the Y to go down to
     * @param toward north/south/east/west for the first segment, or empty = the way it
     *               faces
     * @return the problem, or null if it started
     */
    synchronized String begin(int until, String toward) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return "I am in no world";
        if (!p.isAlive()) return "I am dead; I must respawn first";
        if (!p.onGround()) return "I am not on the ground; wait until I land";
        Direction d = StripMiner.direction(toward, p);
        if (d == null) {
            return String.format("I do not know the direction '%s'; north, "
                    + "south, east or west work, or nothing for the way I am facing", toward);
        }
        if (until < MIN_ELEVATION) {
            return String.format("below Y=%d I do not go down: the world ends "
                    + "at -64 and ground must be left", MIN_ELEVATION);
        }
        ClientWorld world = new ClientWorld(mc.level);
        Route.Point here = MarionetteBot.whereAmI(world, p);
        if (here == null) return "I do not know which tile I am standing on";
        BlockPos where = new BlockPos(here.x(), here.y(), here.z());
        // GOING UP? With the requested Y above, only a saved staircase with its foot
        // nearby will do: it is walked in reverse, without digging. Otherwise the bot
        // does not know where its staircase starts and digs a new one.
        upward = false;
        if (here.y() < until) {
            Staircases.Saved g = Staircases.nearby(where, false, 4.0);
            if (g != null) {
                List<BlockPos> reversed = new ArrayList<>(g.steps());
                java.util.Collections.reverse(reversed);
                return traverse(p, reversed, g.head(), true, g.steps().size());
            }
            // No saved staircase: dig UPWARDS. Otherwise a bot underground had no way
            // back to the surface (dig_down_to only went down, and go_to depends on the
            // path finder).
            if (until > MAX_ELEVATION) {
                return String.format("above Y=%d I do not dig upwards", MAX_ELEVATION);
            }
            upward = true;
        }
        if (!upward && here.y() <= until) {
            return String.format("I am already at Y=%d, which is elevation %d or "
                    + "lower; no need to go down", here.y(), until);
        }
        // GOING DOWN one I already dug? With its head within 4 blocks and reaching at
        // least the requested elevation, it is walked instead of digging another.
        Staircases.Saved g = upward ? null : Staircases.nearby(where, true, 4.0);
        if (g != null && g.foot().getY() <= until + 1 && g.head().getY() > until) {
            List<BlockPos> segment = new ArrayList<>();
            for (BlockPos step : g.steps()) {
                segment.add(step);
                if (step.getY() <= until) break;
            }
            String negative = traverse(p, segment, segment.get(segment.size() - 1), false,
                    segment.size());
            if (negative == null) return null;
            Logbook.note("staircase", "I could not go down the saved one (" + negative
                    + "); digging a new one");
        }
        BlockState stone = Blocks.STONE.defaultBlockState();
        if (!Miner.toolWorks(p, stone)) {
            String saved = Miner.toolInBackpack(p, stone);
            return saved != null
                    ? String.format("I am not starting: in the hotbar I carry no pickaxe "
                            + "that works; I have %s in the backpack, wield it "
                            + "first", saved)
                    : "I am not starting: I carry no pickaxe that works on stone";
        }
        currentDir = d;
        cell = new BlockPos(here.x(), here.y(), here.z());
        elevation = until;
        steps = 0;
        traversal.clear();
        traversal.add(cell);
        traversalValid = true;
        initialHead = cell;
        savedEnd = null;
        climbing = false;
        segmentSteps = 0;
        turnRight = true;
        attempts = 0;
        groundTicks = 0;
        groundAttempts = 0;
        consecutiveTurns = 0;
        candidates = null;
        relocating = false;
        returnDestination = null;
        phase = Phase.CLEAR;
        ticksInPhase = 0;
        outcome = null;
        miner.forgetBrokenTool();
        descending = true;
        Logbook.note("staircase", String.format(
                "starting a staircase %s%s from %d %d %d to Y=%d "
                + "(%d steps)", upward ? "UPWARDS, heading " : "to the ",
                StripMiner.name(d), cell.getX(), cell.getY(), cell.getZ(),
                elevation, Math.abs(cell.getY() - elevation)));
        return null;
    }

    synchronized void stop(String because) {
        if (descending && miner.digging()) miner.stop(because);
        descending = false;
        outcome = because;
    }

    synchronized boolean descending() {
        return descending;
    }

    synchronized void tick() {
        if (!descending) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) { stop("I left the world"); return; }
        if (!p.isAlive()) { finish("I was killed while going down"); return; }
        // While eating the hand is not touched: the use key is held down.
        if (MarionetteBot.eating()) return;
        if (miner.digging()) return;
        String broken = miner.brokenTool();
        if (broken != null) {
            finish(String.format("my %s broke halfway down (I am at "
                    + "Y=%d); I get another and ask me again", broken,
                    cell.getY()));
            return;
        }
        if (++ticksInPhase > STUCK_TICKS) {
            finish(String.format("%d seconds without progress at Y=%d and I do not know "
                    + "why; stopping", STUCK_TICKS / 20, cell.getY()));
            return;
        }
        switch (phase) {
            case CLEAR -> clear(mc, p);
            case ADVANCE -> advance(mc, p);
            case RETURN -> goBack(mc, p);
            case TRAVERSE -> traverseSaved(mc, p);
            case ALTERNATIVE -> alternative(mc, p);
        }
    }

    private void change(Phase fresh) {
        phase = fresh;
        ticksInPhase = 0;
    }

    private void clear(Minecraft mc, LocalPlayer p) {
        if (upward) { clearAbove(mc, p); return; }
        if (!StripMiner.amAt(p, cell)) {
            returnDestination = cell;
            change(Phase.RETURN);
            return;
        }
        if (cell.getY() <= elevation) {
            if (traversalValid) Staircases.note(elevation, traversal);
            finish(String.format("reached the elevation: I am at %d %d %d after %d "
                    + "steps from %d %d %d%s", cell.getX(), cell.getY(),
                    cell.getZ(), steps, initialHead.getX(), initialHead.getY(),
                    initialHead.getZ(), traversalValid
                            ? " (staircase saved: next time I walk it down without digging)"
                            : ""));
            return;
        }
        BlockPos feet = cell.relative(currentDir).below();
        BlockPos head = feet.above();
        BlockPos tall = head.above();
        BlockPos ground = feet.below();

        // Lava or water next to what is about to be opened: it is not opened. But a cave
        // or a lava pocket is usually on ONE side, so before giving up it turns and tries
        // another side. It only stops when all four sides fail.
        for (BlockPos b : new BlockPos[]{tall, head, feet}) {
            if (StripMiner.obstructs(mc, b) && StripMiner.liquidBeside(mc, b)) {
                turn(String.format("lava or water next to %d %d %d", b.getX(),
                        b.getY(), b.getZ()));
                return;
            }
        }
        if (StripMiner.liquid(mc, feet) || StripMiner.liquid(mc, head)
                || StripMiner.liquid(mc, tall) || StripMiner.liquid(mc, ground)) {
            turn(String.format("the step %d %d %d has lava or water",
                    feet.getX(), feet.getY(), feet.getZ()));
            return;
        }
        // The step's floor: if missing (a cave), it is covered with scaffolding and it
        // WAITS for the server to confirm (like the fill job: one click and ten ticks),
        // trying several supports. If there is no way, it turns.
        if (!StripMiner.solid(mc, ground)) {
            if (groundTicks > 0 && --groundTicks > 0) return;
            if (groundAttempts >= 4) {
                groundAttempts = 0;
                turn(String.format("gap without ground at %d %d %d that I could not "
                        + "cover", ground.getX(), ground.getY(), ground.getZ()));
                return;
            }
            int slot = Builder.slotWithScaffold(p);
            if (slot < 0) {
                groundAttempts = 0;
                turn(String.format("gap without ground at %d %d %d and I carry no "
                        + "blocks to cover it", ground.getX(), ground.getY(),
                        ground.getZ()));
                return;
            }
            Direction[] supports = {currentDir.getOpposite(), Direction.DOWN,
                                  currentDir.getClockWise(), currentDir.getCounterClockWise()};
            Direction support = supports[groundAttempts % supports.length];
            groundAttempts++;
            int before = p.getInventory().selected;
            String failure = Builder.placeOf(ground, support, slot);
            p.getInventory().selected = before;
            // With no support on that side, the other one is tried next tick; with the
            // click done, the server gets ten ticks.
            groundTicks = failure != null ? 1 : 10;
            return;
        }
        groundAttempts = 0;
        groundTicks = 0;
        for (BlockPos b : new BlockPos[]{tall, head, feet}) {
            if (!StripMiner.obstructs(mc, b)) continue;
            String failure = miner.begin(b);
            if (failure == null) return;
            if (failure.startsWith("I may break")) {
                finish(String.format("at %d %d %d: %s. I stay at Y=%d", b.getX(),
                        b.getY(), b.getZ(), failure, cell.getY()));
                return;
            }
            // No permission (a building, for example) is just another obstacle: it goes
            // around by turning, it does not stop.
            turn(String.format("I could not open %d %d %d (%s)", b.getX(), b.getY(),
                    b.getZ(), failure));
            return;
        }
        String failure = walker.follow(List.of(StripMiner.point(cell),
                StripMiner.point(feet)), x -> null, 0.4);
        if (failure != null) {
            finish("I could not take the step: " + failure);
            return;
        }
        change(Phase.ADVANCE);
    }

    private void advance(Minecraft mc, LocalPlayer p) {
        if (walker.walking()) return;
        BlockPos feet = upward ? cell.relative(currentDir).above()
                                    : cell.relative(currentDir).below();
        if (StripMiner.amAt(p, feet)) {
            cell = feet;
            traversal.add(cell);
            steps++;
            segmentSteps++;
            attempts = 0;
            consecutiveTurns = 0;
            if (segmentSteps >= SEGMENT
                    && (upward ? cell.getY() < elevation : cell.getY() > elevation)) {
                currentDir = turnRight ? currentDir.getClockWise()
                                         : currentDir.getCounterClockWise();
                turnRight = !turnRight;
                segmentSteps = 0;
                Logbook.note("staircase", String.format(
                        "turning to the %s at Y=%d (%d steps, %d to go)",
                        StripMiner.name(currentDir), cell.getY(), steps,
                        cell.getY() - elevation));
            }
            change(Phase.CLEAR);
            return;
        }
        if (++attempts > 3) {
            attempts = 0;
            turn(String.format("I cannot get down to the step %d %d %d",
                    feet.getX(), feet.getY(), feet.getZ()));
            return;
        }
        change(Phase.CLEAR);
    }

    /**
     * Try another side. A cave, a lava pocket or a gap that cannot be covered almost
     * never surrounds the whole point: turning ninety degrees usually finds rock. After
     * four turns in a row without a step it accepts there is no way, and says at what
     * height it stayed.
     */
    private void turn(String reason) {
        if (++consecutiveTurns > 4) {
            if (upward) {
                // Within four blocks of the elevation, the normal thing is to have come
                // out into the open already (a cave, the surface): the gap without a
                // floor IS the exit. It counts as arriving and the staircase is saved,
                // since from the bottom to here it is whole (otherwise 92 steps went
                // unsaved for two blocks).
                if (elevation - cell.getY() <= 4 && traversalValid) {
                    List<BlockPos> topDown = new ArrayList<>(traversal);
                    java.util.Collections.reverse(topDown);
                    Staircases.note(initialHead.getY(), topDown);
                    finish(String.format("reached the elevation: I am at %d %d %d after "
                            + "%d steps from %d %d %d (climbing; I came out into the air "
                            + "%d from the elevation: %s; staircase saved)", cell.getX(),
                            cell.getY(), cell.getZ(), steps, initialHead.getX(),
                            initialHead.getY(), initialHead.getZ(),
                            elevation - cell.getY(), reason));
                    return;
                }
                finish(String.format("%s, I tried all four sides and find "
                        + "no way to keep climbing; I stay at Y=%d (%d %d %d)",
                        reason, cell.getY(), cell.getX(), cell.getY(),
                        cell.getZ()));
                return;
            }
            // All four sides closed. Before giving up: nearby there may be a way around
            // the structure, such as a 3-block drop. A nearby, lower spot where it can
            // stand is looked for and the path finder is asked for a way there, with
            // permission to build: gaps are crossed by placing blocks, since it does no
            // parkour.
            searchAlternative(reason);
            return;
        }
        currentDir = currentDir.getClockWise();
        segmentSteps = 0;
        groundTicks = 0;
        groundAttempts = 0;
        Logbook.note("staircase", String.format("%s: turning to the %s at Y=%d",
                reason, StripMiner.name(currentDir), cell.getY()));
        change(Phase.CLEAR);
    }

    /**
     * Stops by its own decision (arriving, or being unable to continue) and NOTIFIES: the
     * staircase lives in the body and the brain does not learn it stopped until someone
     * asks; otherwise the bot sits against a building without saying anything. The notice
     * goes through the same channel as hunger or torches, and the brain tells it in the
     * chat.
     */
    /**
     * Starts walking a SAVED staircase (without digging): the walker follows the steps as
     * they are, which is what the path finder could not manage with 50 blocks of zig-zag.
     *
     * @return null if it started, or the reason
     */
    private String traverse(LocalPlayer p, List<BlockPos> segment, BlockPos end,
                            boolean climb, int stairSteps) {
        List<Route.Point> route = new ArrayList<>();
        for (BlockPos step : segment) route.add(StripMiner.point(step));
        String failure = walker.follow(route, x -> null, 0.4);
        if (failure != null) return "I could not get going: " + failure;
        cell = segment.get(0);
        elevation = end.getY();
        steps = stairSteps;
        savedEnd = end;
        climbing = climb;
        traversalValid = false;   // not saved again: it already is
        phase = Phase.TRAVERSE;
        ticksInPhase = 0;
        outcome = null;
        descending = true;
        Logbook.note("staircase", String.format("%s my earlier staircase: "
                + "%d steps to %d %d %d, without digging", climb ? "climbing" : "walking down",
                stairSteps, end.getX(), end.getY(), end.getZ()));
        return null;
    }

    private void traverseSaved(Minecraft mc, LocalPlayer p) {
        if (walker.walking()) { ticksInPhase = 0; return; }
        BlockPos me = p.blockPosition();
        if (StripMiner.amAt(p, savedEnd)) {
            finish(String.format("%s: I am at %d %d %d by my earlier "
                    + "staircase (%d steps, without digging)",
                    climbing ? "I climbed the staircase" : "reached the elevation",
                    savedEnd.getX(), savedEnd.getY(), savedEnd.getZ(),
                    steps));
            return;
        }
        finish(String.format("I could not %s the whole saved staircase: I stayed "
                + "at %d %d %d (a covered or broken step?); ask me dig_down_to "
                + "again to continue from here%s", climbing ? "climb" : "go down",
                me.getX(), me.getY(), me.getZ(),
                climbing ? "" : ", or I dig from here"));
    }

    /**
     * A step UPWARDS: the block above my head is opened (to be able to jump), and the two
     * of the next step (feet and head, one block forward and one up); the block I will
     * step on is the one in front of me at my height, and if missing (a cave) it is
     * covered with scaffolding. With the same brakes as going down: lava or water next to
     * it, plus gravel or sand ABOVE what is opened, which would fall on me.
     */
    private void clearAbove(Minecraft mc, LocalPlayer p) {
        if (!StripMiner.amAt(p, cell)) {
            returnDestination = cell;
            change(Phase.RETURN);
            return;
        }
        if (cell.getY() >= elevation) {
            if (traversalValid) {
                List<BlockPos> topDown = new ArrayList<>(traversal);
                java.util.Collections.reverse(topDown);
                Staircases.note(initialHead.getY(), topDown);
            }
            finish(String.format("reached the elevation: I am at %d %d %d after %d "
                    + "steps from %d %d %d (climbing%s)", cell.getX(),
                    cell.getY(), cell.getZ(), steps, initialHead.getX(),
                    initialHead.getY(), initialHead.getZ(), traversalValid
                            ? "; staircase saved: next time I walk it down or up without digging"
                            : ""));
            return;
        }
        BlockPos feet = cell.relative(currentDir).above();
        BlockPos head = feet.above();
        BlockPos aboveMe = cell.above().above();
        BlockPos ground = feet.below();
        for (BlockPos b : new BlockPos[]{aboveMe, head, feet}) {
            if (StripMiner.obstructs(mc, b) && StripMiner.liquidBeside(mc, b)) {
                turn(String.format("lava or water next to %d %d %d", b.getX(),
                        b.getY(), b.getZ()));
                return;
            }
        }
        if (StripMiner.liquid(mc, feet) || StripMiner.liquid(mc, head)
                || StripMiner.liquid(mc, aboveMe) || StripMiner.liquid(mc, ground)) {
            turn(String.format("the step %d %d %d has lava or water",
                    feet.getX(), feet.getY(), feet.getZ()));
            return;
        }
        if (StripMiner.falls(mc, head.above()) || StripMiner.falls(mc, aboveMe.above())) {
            turn(String.format("gravel or sand above the step %d %d %d; it "
                    + "would fall on me", feet.getX(), feet.getY(), feet.getZ()));
            return;
        }
        if (!StripMiner.solid(mc, ground)) {
            if (groundTicks > 0 && --groundTicks > 0) return;
            if (groundAttempts >= 4) {
                groundAttempts = 0;
                turn(String.format("gap without ground at %d %d %d that I could not "
                        + "cover", ground.getX(), ground.getY(), ground.getZ()));
                return;
            }
            int slot = Builder.slotWithScaffold(p);
            if (slot < 0) {
                groundAttempts = 0;
                turn(String.format("gap without ground at %d %d %d and I carry no "
                        + "blocks to cover it", ground.getX(), ground.getY(),
                        ground.getZ()));
                return;
            }
            Direction[] supports = {Direction.DOWN, currentDir,
                                  currentDir.getClockWise(), currentDir.getCounterClockWise()};
            Direction support = supports[groundAttempts % supports.length];
            groundAttempts++;
            int before = p.getInventory().selected;
            String failure = Builder.placeOf(ground, support, slot);
            p.getInventory().selected = before;
            groundTicks = failure != null ? 1 : 10;
            return;
        }
        groundAttempts = 0;
        groundTicks = 0;
        for (BlockPos b : new BlockPos[]{aboveMe, head, feet}) {
            if (!StripMiner.obstructs(mc, b)) continue;
            String failure = miner.begin(b);
            if (failure == null) return;
            if (failure.startsWith("I may break")) {
                finish(String.format("at %d %d %d: %s. I stay at Y=%d", b.getX(),
                        b.getY(), b.getZ(), failure, cell.getY()));
                return;
            }
            turn(String.format("I could not open %d %d %d (%s)", b.getX(), b.getY(),
                    b.getZ(), failure));
            return;
        }
        String failure = walker.follow(List.of(StripMiner.point(cell),
                StripMiner.point(feet)), x -> null, 0.4);
        if (failure != null) {
            finish("I could not take the step upwards: " + failure);
            return;
        }
        change(Phase.ADVANCE);
    }

    private void finish(String because) {
        stop(because);
        Needs.warn("staircase:" + Integer.toHexString(because.hashCode()),
                "staircase: " + because);
    }

    private void searchAlternative(String reason) {
        Minecraft mc = Minecraft.getInstance();
        ClientWorld world = new ClientWorld(mc.level);
        java.util.List<BlockPos> list = new java.util.ArrayList<>();
        for (int dy = 1; dy <= ALT_DESCENT; dy++) {
            int y = cell.getY() - dy;
            for (int dx = -ALT_RADIUS; dx <= ALT_RADIUS; dx++) {
                for (int dz = -ALT_RADIUS; dz <= ALT_RADIUS; dz++) {
                    int x = cell.getX() + dx, z = cell.getZ() + dz;
                    if (!world.canStand(x, y, z)) continue;
                    list.add(new BlockPos(x, y, z));
                }
            }
        }
        if (list.isEmpty()) {
            finish(String.format("%s, I tried all four sides and see nearby "
                    + "no lower spot to continue; I stay at Y=%d "
                    + "(%d %d %d)", reason, cell.getY(), cell.getX(),
                    cell.getY(), cell.getZ()));
            return;
        }
        // Lowest first and, at the same height, closest: the goal is to GO DOWN, not to
        // take a stroll.
        BlockPos from = cell;
        list.sort((a, b) -> {
            if (a.getY() != b.getY()) return Integer.compare(a.getY(), b.getY());
            return Double.compare(a.distSqr(from), b.distSqr(from));
        });
        candidates = list.size() > 12 ? new java.util.ArrayList<>(list.subList(0, 12)) : list;
        consecutiveTurns = 0;
        Logbook.note("staircase", String.format("%s: looking for another spot to "
                + "keep going down (%d candidates)", reason, candidates.size()));
        change(Phase.ALTERNATIVE);
    }

    /**
     * One candidate per tick: searching routes is costly, and this runs on the game
     * thread.
     */
    private void alternative(Minecraft mc, LocalPlayer p) {
        if (walker.walking()) return;
        if (candidates == null || candidates.isEmpty()) {
            finish(String.format("I found no path to any lower spot "
                    + "nearby; I stay at Y=%d (%d %d %d)", cell.getY(),
                    cell.getX(), cell.getY(), cell.getZ()));
            return;
        }
        BlockPos c = candidates.remove(0);
        ClientWorld world = new ClientWorld(mc.level);
        Route.Point here = MarionetteBot.whereAmI(world, p);
        if (here == null) { finish("I got lost: I do not know which tile I am on"); return; }
        Route.Result r = Route.search(world, here, StripMiner.point(c),
                new Route.Options(MarionetteBot.safeFall(p.getHealth()), 6000, true)
                        .withDeadline(30));
        if (!r.hasRoute()) return;                     // the next one, next tick
        String failure = walker.follow(r.steps(), x -> null, 0.4);
        if (failure != null) return;
        returnDestination = c;
        relocating = true;
        Logbook.note("staircase", String.format("going to %d %d %d to continue "
                + "going down from there", c.getX(), c.getY(), c.getZ()));
        change(Phase.RETURN);
    }

    private void goBack(Minecraft mc, LocalPlayer p) {
        if (walker.walking()) return;
        if (returnDestination == null || StripMiner.amAt(p, returnDestination)) {
            if (relocating && returnDestination != null) {
                cell = returnDestination;
                traversalValid = false;
                relocating = false;
                consecutiveTurns = 0;
                segmentSteps = 0;
                Logbook.note("staircase", String.format(
                        "continuing the staircase from %d %d %d to the %s",
                        cell.getX(), cell.getY(), cell.getZ(),
                        StripMiner.name(currentDir)));
            }
            returnDestination = null;
            change(Phase.CLEAR);
            return;
        }
        // The way-back tile is blocked (gravel): clear it if in reach.
        for (BlockPos c : new BlockPos[]{returnDestination.above(), returnDestination}) {
            if (!StripMiner.obstructs(mc, c) || StripMiner.liquidBeside(mc, c)) continue;
            if (p.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(c)) > 4.3 * 4.3) break;
            if (miner.begin(c) == null) {
                Logbook.note("staircase", String.format(
                        "the way-back tile %d %d %d is blocked: clearing it",
                        c.getX(), c.getY(), c.getZ()));
                ticksInPhase = 0;
                return;
            }
        }
        ClientWorld world = new ClientWorld(mc.level);
        Route.Point here = MarionetteBot.whereAmI(world, p);
        if (here == null) { finish("I got lost: I do not know which tile I am on"); return; }
        Route.Result r = Route.search(world, here, StripMiner.point(returnDestination),
                new Route.Options(MarionetteBot.safeFall(p.getHealth()), 4000)
                        .withDeadline(30));
        if (!r.hasRoute()) {
            finish(String.format("I find no way back to the staircase "
                    + "(%d %d %d): %s", returnDestination.getX(), returnDestination.getY(),
                    returnDestination.getZ(), r.reason()));
            return;
        }
        String failure = walker.follow(r.steps(), x -> null, 0.4);
        if (failure != null) { finish("I could not get back to the staircase: " + failure); return; }
        ticksInPhase = 0;
    }

    synchronized String state() {
        if (!descending) {
            return String.format("{\"descending\":false,\"outcome\":\"%s\"}",
                    Request.escape(outcome));
        }
        return String.format(
                "{\"descending\":true,\"elevation\":%d,\"y\":%d,\"missing\":%d,"
                + "\"stair_steps\":%d,\"heading\":\"%s\",\"phase\":\"%s\","
                + "\"cell\":{\"x\":%d,\"y\":%d,\"z\":%d}}",
                elevation, cell.getY(), cell.getY() - elevation, steps,
                StripMiner.name(currentDir), phase.name().toLowerCase(),
                cell.getX(), cell.getY(), cell.getZ());
    }
}
