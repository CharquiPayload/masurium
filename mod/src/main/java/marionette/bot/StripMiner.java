package marionette.bot;

import marionette.common.Logbook;
import marionette.common.Request;
import marionette.common.Route;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Strip mining: a straight 1x2 tunnel with no end, with side branches, that takes the
 * ores APPEARING in its walls. The way a person mines, not the way X-ray mines.
 *
 * <p>The bot strip mines instead of using X-ray: asking the server where iron_ore is
 * within 32 blocks, buried or not, and walking straight to it is off the table. And the
 * strip has no length: it goes on until stopped or until something stops it (lava, water,
 * a broken pickaxe, a permission). It is a BODY behaviour on purpose: the brain starts it
 * with one call and ends its turn; mining iron hit by hit took dozens of calls per turn
 * and did not fit within the two-minute limit.
 *
 * <p><b>What it sees and what it does not.</b> It only digs ores left exposed (with a
 * face to the air) within four blocks of where it stands, and the {@link Miner} also
 * requires really seeing them. Every ore that appears when digging the previous one is
 * legitimately visible: that way a vein is followed by contact, never knowing what is
 * behind the rock. The server does not give it a single ore coordinate.
 *
 * <p><b>Never downwards.</b> When mining, the bot must not dig vertically down: it has no
 * way back up. The tunnel floor is never dug, and ores at floor level are only taken if
 * they are beside it, never under the feet.
 *
 * <p><b>Following the vein.</b> Otherwise, on finding an ore it only mined what belonged
 * to the tunel and left the rest of the vein below. When no ore is left within reach from
 * the tunnel tile, the next EXPOSED one nearby is looked for and the bot walks to a tile
 * from which to dig it (by contact, like a person, never what is behind the rock), within
 * a limit: at most {@value #VEIN_DESCENT_MAX} blocks below the tunnel floor and {@value
 * #VEIN_FAR_MAX} away, and never digging the block holding it up. It is the only
 * exception to "never downwards", and it is narrow because the way back has to be on
 * foot; if the vein leaves it in a hole, it gets out with the usual {@link Rescue}
 * (climbing by placing blocks under its own feet). When the vein ends it returns to the
 * tunnel tile and the strip goes on.
 *
 * <p><b>Before breaking, look behind.</b> A block with lava or water next to it is not
 * broken: in the main tunnel that is the end of the strip (and it says where), in a
 * branch it is the end of the branch. Gravel or sand falling when a gap opens is dug
 * again by itself: the tile stops being free and the loop takes it up again.
 *
 * <p>The {@link Torchbearer} places torches when block light reaches zero, which is when
 * they matter (every ten or twelve blocks), not blindly every N.
 */
final class StripMiner {

    /** Digging reach, with margin below the server's 4.5. */
    private static final double REACH = 4.3;
    /** How many blocks of the main tunnel between the two branches. */
    private static final int EVERY_BRANCHES = 3;
    /** Length of each side branch. */
    private static final int BRANCH_LENGTH = 8;
    /** Radius in which items dropped while digging are picked up. */
    private static final double PICKUP_RADIUS = 3.0;
    /** Ticks without progress in a phase before calling it stuck. */
    private static final int STUCK_TICKS = 20 * 40;
    /**
     * Following a vein: how many blocks below the tunnel floor it goes down, how far
     * (horizontally) from the tunnel tile it strays, and how many detours it makes from
     * the same tile.
     */
    private static final int VEIN_DESCENT_MAX = 4;
    private static final int VEIN_FAR_MAX = 8;
    private static final int VEIN_STEPS_MAX = 16;

    private enum Phase { CLEAR, ADVANCE, MINERAL, VEIN, PICK_UP, RETURN }

    private final Walker walker;
    private final Miner miner;

    private boolean mining;
    private String outcome = "I have not strip mined anything";
    private Phase phase = Phase.CLEAR;
    private int ticksInPhase;

    /**
     * Where the main tunnel goes, and where the current segment goes (a branch goes
     * sideways).
     */
    private Direction heading;
    private Direction currentDir;
    /** The tunnel tile where it has to stand now. */
    private BlockPos cell;
    /** Length of the current segment; -1 is endless (the main tunnel). */
    private int segmentLimit = -1;
    private int segmentProgress;
    private int mainProgress;

    private boolean withBranches = true;
    private boolean inBranch;
    /** 0 = the left branch is still missing, 1 = the right one is missing, 2 = done. */
    private int branchSide;
    private BlockPos mainCell;
    /**
     * At which progress of the main tunnel the branches were already made, so they are
     * not repeated when coming back to the same tile.
     */
    private int branchesDoneAt = -1;
    private int branchesDone;

    private BlockPos returnDestination;
    private BlockPos currentMineral;
    private final Set<BlockPos> skippedMinerals = new HashSet<>();
    /**
     * Following a vein outside the tunnel: where I am going, for which ore, how many
     * detours from this tile, and the ones I could not reach.
     */
    private BlockPos veinDestination;
    private BlockPos veinMineral;
    private int veinSteps;
    private final Set<BlockPos> veinSkipped = new HashSet<>();
    /**
     * Ticks of waiting after covering a gap for the vein (the server takes a moment to
     * confirm the block).
     */
    private int veinWait;
    private final Set<Integer> skippedObjects = new HashSet<>();
    /** The item on the ground it is heading for now, or -1. One attempt per item. */
    private int currentObject = -1;
    /** Already warned about the full backpack in this strip: once is enough. */
    private boolean warnedBackpack;
    /** Blocks already opened to reach an item: one per gap, not in a loop. */
    private final Set<BlockPos> opened = new HashSet<>();
    /**
     * Maximum ticks chasing an item: redstone dust falls into gaps in the wall and cannot
     * be picked up; eight seconds and on to something else.
     */
    private static final int TICKS_PER_OBJECT = 20 * 8;
    private int minerals;
    /**
     * The last strip that stopped, to RESUME it if relaunched with the same heading near
     * its front. Otherwise, relaunched from a branch after a pickaxe broke, it started a
     * new tunnel system three blocks away from the old one.
     */
    private record Last(Direction heading, BlockPos front, int mainProgress,
                          int branchesDoneAt, int branchesDone, int minerals,
                          boolean withBranches, String dimension) {}
    private static final int RESUME_NEAR = 24;
    private Last last;
    private int advanceAttempts;
    private int groundTicks;
    private int groundAttempts;

    StripMiner(Walker walker, Miner miner) {
        this.walker = walker;
        this.miner = miner;
    }

    // --- start and stop ---------------------------------------------------------

    /**
     * @param toward north/south/east/west, or empty for the way it faces
     * @param branches whether side branches come out every {@value #EVERY_BRANCHES}
     *                 blocks
     * @return the problem, or null if it started
     */
    synchronized String begin(String toward, boolean branches) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return "I am in no world";
        if (!p.isAlive()) return "I am dead; I must respawn first";
        if (!p.onGround()) return "I am not on the ground; wait until I land";

        Direction d = direction(toward, p);
        if (d == null) {
            return String.format("I do not know the direction '%s'; north, "
                    + "south, east or west work, or nothing for the way I am facing", toward);
        }
        // The pickaxe is checked BEFORE walking, as in fill jobs: without one that works
        // on stone, the whole strip is a stroll.
        BlockState stone = Blocks.STONE.defaultBlockState();
        if (!Miner.toolWorks(p, stone)) {
            String saved = Miner.toolInBackpack(p, stone);
            return saved != null
                    ? String.format("I am not starting: in the hotbar I carry no pickaxe "
                            + "that works; I have %s in the backpack, wield it "
                            + "first", saved)
                    : "I am not starting: I carry no pickaxe that works on stone";
        }

        ClientWorld world = new ClientWorld(mc.level);
        Route.Point here = MarionetteBot.whereAmI(world, p);
        if (here == null) return "I do not know which tile I am standing on";

        BlockPos where = new BlockPos(here.x(), here.y(), here.z());
        boolean resumeFlag = last != null && last.heading() == d
                && last.dimension().equals(Places.currentDimension())
                && Math.abs(last.front().getY() - where.getY()) <= 4
                && Math.max(Math.abs(last.front().getX() - where.getX()),
                            Math.abs(last.front().getZ() - where.getZ()))
                   <= RESUME_NEAR;
        heading = d;
        currentDir = d;
        cell = resumeFlag ? last.front() : where;
        segmentLimit = -1;
        mainProgress = resumeFlag ? last.mainProgress() : 0;
        segmentProgress = mainProgress;
        withBranches = resumeFlag ? last.withBranches() : branches;
        inBranch = false;
        branchSide = 0;
        mainCell = null;
        branchesDoneAt = resumeFlag ? last.branchesDoneAt() : -1;
        branchesDone = resumeFlag ? last.branchesDone() : 0;
        returnDestination = null;
        currentMineral = null;
        skippedMinerals.clear();
        veinDestination = null;
        veinMineral = null;
        veinSteps = 0;
        veinSkipped.clear();
        skippedObjects.clear();
        currentObject = -1;
        warnedBackpack = false;
        opened.clear();
        minerals = resumeFlag ? last.minerals() : 0;
        advanceAttempts = 0;
        groundTicks = 0;
        groundAttempts = 0;
        // It starts with MINERAL and not CLEAR on purpose: first whatever is already in
        // sight and lying around, and then the tunnel. When resuming, first back to the
        // front (CLEAR already sends it to RETURN if it is not on the tile).
        phase = resumeFlag && !amAt(p, cell) ? Phase.CLEAR : Phase.MINERAL;
        ticksInPhase = 0;
        outcome = null;
        miner.forgetBrokenTool();
        mining = true;
        if (resumeFlag) {
            Logbook.note("strip_mine", String.format(
                    "resuming the strip mine to the %s from its front at %d %d %d "
                    + "(%d blocks, %d minerals); I do not open another",
                    name(d), cell.getX(), cell.getY(), cell.getZ(),
                    mainProgress, minerals));
        } else {
            Logbook.note("strip_mine", String.format(
                    "starting a strip mine to the %s from %d %d %d%s",
                    name(d), cell.getX(), cell.getY(), cell.getZ(),
                    branches ? String.format(" with branches of %d every %d", BRANCH_LENGTH,
                            EVERY_BRANCHES) : " without branches"));
        }
        return null;
    }

    synchronized void stop(String because) {
        if (mining) {
            // Save the front to be able to resume: in a branch, the front is the main
            // tunnel tile it came out of.
            last = new Last(heading, inBranch && mainCell != null
                    ? mainCell : cell, mainProgress, branchesDoneAt,
                    branchesDone, minerals, withBranches, Places.currentDimension());
        }
        if (mining && miner.digging()) miner.stop(because);
        mining = false;
        outcome = because;
    }

    synchronized boolean mining() {
        return mining;
    }

    // --- the tick ------------------------------------------------------------------

    synchronized void tick() {
        if (!mining) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) { stop("I left the world"); return; }
        if (!p.isAlive()) { finish("I was killed while mining"); return; }

        // While eating the hand is not touched: the use key is held down.
        if (MarionetteBot.eating()) return;
        // A half-dug block is finished before deciding anything else.
        if (miner.digging()) return;

        String broken = miner.brokenTool();
        if (broken != null) {
            finish(String.format("my %s broke halfway through the tunnel; I get "
                    + "another (or give me one) and ask me again", broken));
            return;
        }
        if (++ticksInPhase > STUCK_TICKS) {
            finish(String.format("%d seconds without progress in phase %s "
                    + "and I do not know why; stopping so as not to go in circles",
                    STUCK_TICKS / 20, phase.name().toLowerCase()));
            return;
        }

        switch (phase) {
            case MINERAL -> mineral(mc, p);
            case VEIN -> vein(mc, p);
            case PICK_UP -> pickUp(mc, p);
            case CLEAR -> clear(mc, p);
            case ADVANCE -> advance(mc, p);
            case RETURN -> goBack(mc, p);
        }
    }

    private void change(Phase fresh) {
        phase = fresh;
        ticksInPhase = 0;
    }

    // --- ores in sight -------------------------------------------------------------

    private void mineral(Minecraft mc, LocalPlayer p) {
        if (currentMineral != null) {
            if (mc.level.getBlockState(currentMineral).isAir()) minerals++;
            currentMineral = null;
        }
        BlockPos next = mineralInSight(mc, p);
        if (next == null) {
            // Nothing within reach from here: does the vein go on a little farther?
            if (veinWait > 0) { veinWait--; return; }
            if (followVein(mc, p)) return;
            change(Phase.PICK_UP);
            return;
        }
        String what = Miner.nameOf(mc.level.getBlockState(next));
        String failure = miner.begin(next);
        if (failure != null) {
            // No insisting on the same one: either it cannot be seen from here, or it
            // drops nothing with this pickaxe. It is noted once and the tunnel goes on.
            skippedMinerals.add(next);
            veinSkipped.add(next);
            Logbook.note("strip_mine", String.format("I leave %s at %d %d %d: %s",
                    what, next.getX(), next.getY(), next.getZ(),
                    failure));
            return;
        }
        currentMineral = next;
        ticksInPhase = 0;
        Logbook.note("strip_mine", String.format("I see %s at %d %d %d and dig it",
                what, next.getX(), next.getY(), next.getZ()));
    }

    /**
     * The nearest exposed ore that can be dug from here without opening the door to
     * anything: with a face to the air, within reach, without lava or water next to it,
     * never under the feet, and in the ceiling only if nothing above it would fall.
     */
    private BlockPos mineralInSight(Minecraft mc, LocalPlayer p) {
        BlockPos feet = p.blockPosition();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        int r = (int) Math.ceil(REACH);
        // Up to THREE above the feet: a player digs the block showing through a gap in
        // the ceiling by looking up, and with a cap of two a bot left three diamonds
        // above a gap in its tunnel's ceiling.
        for (BlockPos b : BlockPos.betweenClosed(feet.offset(-r, -1, -r),
                                                  feet.offset(r, 3, r))) {
            BlockState state = mc.level.getBlockState(b);
            if (!isMineral(state)) continue;
            if (skippedMinerals.contains(b)) continue;
            // Under the feet, never: it is the tunnel floor or, following a vein, the
            // block holding it up. Beside and lower, yes.
            if (b.equals(feet.below())) continue;
            if (veinSkipped.contains(b)) continue;
            // Measured the way the Miner measures it (from the feet), or here it accepted
            // at 4.3 what the Miner rejected at 4.8.
            double d = Math.sqrt(p.distanceToSqr(Vec3.atCenterOf(b)));
            if (d > REACH || d >= bestD) continue;
            if (!exposed(mc, b)) continue;
            if (liquidBeside(mc, b)) continue;
            if (b.getY() >= feet.getY() + 2 && falls(mc, b.above())) continue;
            best = b.immutable();
            bestD = d;
        }
        return best;
    }

    static boolean isMineral(BlockState state) {
        String id = Miner.nameOf(state);
        return id.endsWith("_ore") || id.equals("ancient_debris");
    }

    private static boolean exposed(Minecraft mc, BlockPos b) {
        for (Direction d : Direction.values()) {
            BlockPos v = b.relative(d);
            if (mc.level.getBlockState(v).getCollisionShape(mc.level, v).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    static boolean liquidBeside(Minecraft mc, BlockPos b) {
        for (Direction d : Direction.values()) {
            if (!mc.level.getFluidState(b.relative(d)).isEmpty()) return true;
        }
        return false;
    }

    static boolean liquid(Minecraft mc, BlockPos b) {
        return !mc.level.getFluidState(b).isEmpty();
    }

    static boolean falls(Minecraft mc, BlockPos b) {
        return mc.level.getBlockState(b).getBlock() instanceof FallingBlock;
    }

    static boolean solid(Minecraft mc, BlockPos b) {
        return !mc.level.getBlockState(b).getCollisionShape(mc.level, b).isEmpty();
    }

    static boolean obstructs(Minecraft mc, BlockPos b) {
        BlockState e = mc.level.getBlockState(b);
        return !e.isAir() && !e.canBeReplaced();
    }

    // --- following the vein ------------------------------------------------------

    /**
     * An exposed ore nearby but out of reach, and a tile to dig it from; if there is one,
     * it sets off (VEIN phase).
     *
     * <p>Only what has a face to the air, as a person would see it: every ore dug leaves
     * the next one exposed, and that way the vein is followed by contact without ever
     * knowing what lies behind the rock. Limits in {@link #VEIN_DESCENT_MAX}, {@link
     * #VEIN_FAR_MAX} and {@link #VEIN_STEPS_MAX}; the block holding it up is never dug
     * (which is why the standing tiles go BESIDE the ore, never on top).
     */
    private boolean followVein(Minecraft mc, LocalPlayer p) {
        if (veinSteps >= VEIN_STEPS_MAX) return false;
        BlockPos feet = p.blockPosition();
        ClientWorld world = new ClientWorld(mc.level);
        Route.Point here = MarionetteBot.whereAmI(world, p);
        if (here == null) return false;
        int yMin = cell.getY() - VEIN_DESCENT_MAX;
        int yMax = cell.getY() + 3;
        List<BlockPos> candidates = new ArrayList<>();
        for (BlockPos b : BlockPos.betweenClosed(
                feet.offset(-6, yMin - feet.getY(), -6),
                feet.offset(6, yMax - feet.getY(), 6))) {
            if (!isMineral(mc.level.getBlockState(b))) continue;
            if (skippedMinerals.contains(b) || veinSkipped.contains(b)) continue;
            if (Math.max(Math.abs(b.getX() - cell.getX()),
                         Math.abs(b.getZ() - cell.getZ())) > VEIN_FAR_MAX) continue;
            if (!exposed(mc, b) || liquidBeside(mc, b)) continue;
            candidates.add(b.immutable());
        }
        candidates.sort(java.util.Comparator.comparingDouble(
                b -> p.distanceToSqr(Vec3.atCenterOf(b))));
        for (BlockPos b : candidates) {
            for (BlockPos foot : feetForDigging(world, b)) {
                if (foot.getY() < yMin) continue;
                // Would it be visible from there? If not, do not even walk: that was the
                // iron loop (go, "I cannot see it", come back, repeat).
                Vec3 eyes = new Vec3(foot.getX() + 0.5,
                        foot.getY() + p.getEyeHeight(), foot.getZ() + 0.5);
                if (Miner.whatIsInTheWay(p, eyes, b) != null) continue;
                Route.Result r = Route.search(world, here, point(foot),
                        new Route.Options(MarionetteBot.safeFall(p.getHealth()), 1500)
                                .withDeadline(20));
                if (!r.hasRoute()) continue;
                String failure = walker.follow(r.steps(), x -> null, 0.4);
                if (failure != null) continue;
                veinDestination = foot;
                veinMineral = b;
                veinSteps++;
                change(Phase.VEIN);
                Logbook.note("strip_mine", String.format(
                        "following the vein: %s at %d %d %d, moving to %d %d %d",
                        Miner.nameOf(mc.level.getBlockState(b)),
                        b.getX(), b.getY(), b.getZ(),
                        foot.getX(), foot.getY(), foot.getZ()));
                return true;
            }
            // No room to stand beside it: is opening the head block of an adjacent tile,
            // or covering a gap in the floor, enough? One block, with the usual brakes,
            // as anyone would (four diamonds one block away, behind the head of the next
            // step, and a gap in the floor).
            if (makeRoomBeside(mc, p, world, b)) return true;
            veinSkipped.add(b);
        }
        return false;
    }

    /**
     * Opens the head or covers the floor of ONE tile adjacent to the ore so it can stand
     * there. @return true if this tick did one of the two.
     */
    private boolean makeRoomBeside(Minecraft mc, LocalPlayer p, ClientWorld world,
                                    BlockPos b) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos c = b.relative(d).immutable();
            BlockPos head = c.above();
            BlockPos ground = c.below();
            if (world.canStand(c.getX(), c.getY(), c.getZ())) continue;
            if (solid(mc, c)) continue;                 // feet in rock: not a step
            if (p.distanceToSqr(Vec3.atCenterOf(c)) > REACH * REACH) continue;
            if (liquid(mc, c) || liquidBeside(mc, head) || liquidBeside(mc, ground)) continue;
            if (obstructs(mc, head)) {
                if (opened.contains(head) || falls(mc, head.above())) continue;
                if (miner.begin(head) == null) {
                    opened.add(head);
                    Logbook.note("strip_mine", String.format(
                            "opening %d %d %d to be able to stand next to the ore at %d %d %d",
                            head.getX(), head.getY(), head.getZ(),
                            b.getX(), b.getY(), b.getZ()));
                    return true;
                }
                continue;
            }
            if (!solid(mc, ground)) {
                if (opened.contains(ground)) continue;
                int slot = Builder.slotWithScaffold(p);
                if (slot < 0) continue;
                opened.add(ground);
                int before = p.getInventory().selected;
                String failure = null;
                for (Direction support : new Direction[]{Direction.DOWN, d.getOpposite(),
                        d.getClockWise(), d.getCounterClockWise()}) {
                    failure = Builder.placeOf(ground, support, slot);
                    if (failure == null) break;
                }
                p.getInventory().selected = before;
                if (failure == null) {
                    veinWait = 12;
                    Logbook.note("strip_mine", String.format(
                            "covering the floor gap at %d %d %d to stand next to the ore at %d %d %d",
                            ground.getX(), ground.getY(), ground.getZ(),
                            b.getX(), b.getY(), b.getZ()));
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Standing tiles from which an ore is within reach: beside it, at its height, one
     * block lower or higher. Never on top: that would be digging under the feet.
     */
    private static List<BlockPos> feetForDigging(ClientWorld world, BlockPos b) {
        List<BlockPos> feet = new ArrayList<>();
        for (int dy : new int[]{0, -1, 1}) {
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos c = b.relative(d).offset(0, dy, 0);
                if (world.canStand(c.getX(), c.getY(), c.getZ())) feet.add(c);
            }
        }
        return feet;
    }

    private void vein(Minecraft mc, LocalPlayer p) {
        if (walker.walking()) return;
        if (veinDestination != null && amAt(p, veinDestination)) {
            veinDestination = null;
            change(Phase.MINERAL);
            return;
        }
        // I did not get there: that ore cannot be reached from where I thought; on to
        // another.
        if (veinMineral != null) veinSkipped.add(veinMineral);
        veinDestination = null;
        change(Phase.MINERAL);
    }

    // --- picking up what dropped -------------------------------------------------

    private void pickUp(Minecraft mc, LocalPlayer p) {
        if (walker.walking()) {
            if (ticksInPhase > TICKS_PER_OBJECT) {
                walker.stop("that object will not let itself be picked up");
                if (currentObject >= 0) skippedObjects.add(currentObject);
                currentObject = -1;
            }
            return;
        }
        // Arrived (or gave up). If the item is still there, it cannot be picked up from
        // where one can stand: no insisting on it (otherwise it circles the dust of five
        // redstone ores).
        if (currentObject >= 0) {
            skippedObjects.add(currentObject);
            currentObject = -1;
        }
        ItemEntity near = null;
        double bestD = Double.MAX_VALUE;
        for (ItemEntity e : mc.level.getEntitiesOfClass(ItemEntity.class,
                p.getBoundingBox().inflate(PICKUP_RADIUS))) {
            if (skippedObjects.contains(e.getId())) continue;
            // With a full backpack it does not go after what does not fit (otherwise it
            // circles twenty redstone dust with 36 of 36 slots taken). It warns once and
            // goes on.
            if (!fits(p, e.getItem())) {
                skippedObjects.add(e.getId());
                if (!warnedBackpack) {
                    warnedBackpack = true;
                    Needs.warn("strip_mine:backpack", String.format(
                            "my backpack is full and I am leaving on the ground "
                            + "what I dig (for example %s); tell me what to toss or "
                            + "empty me", WeaponPicker.name(e.getItem())));
                }
                continue;
            }
            double d = p.distanceTo(e);
            if (d < bestD) { bestD = d; near = e; }
        }
        if (near == null) {
            // Nothing else to take: back to the tunnel tile and carry on.
            returnDestination = cell;
            change(Phase.RETURN);
            return;
        }
        // What is almost touching goes in by itself; otherwise it is fetched on foot, but
        // only if one can stand there (it does not jump into a gap for a piece of iron).
        // Arriving within 0.6 is enough: the inventory magnet does the rest, and asking
        // for 0.3 meant never arriving in an irregular gap.
        ClientWorld world = new ClientWorld(mc.level);
        BlockPos where = near.blockPosition();
        Route.Point here = MarionetteBot.whereAmI(world, p);
        Route.Point overThere = new Route.Point(where.getX(), where.getY(), where.getZ());
        if (here != null && !world.canStand(overThere.x(), overThere.y(), overThere.z())) {
            // The ore's own gap: digging in the wall, what drops stays in a one-block
            // hole where it cannot stand; the bot has to break a few blocks to get those
            // diamonds. Only what is needed is opened: at head height, the block BELOW
            // (the item falls to the feet and the gap becomes two tall); at floor level,
            // the gap's ceiling. With the usual brakes.
            BlockPos open = where.getY() > cell.getY() ? where.below() : where.above();
            if (!opened.contains(open) && obstructs(mc, open)
                    && !liquidBeside(mc, open) && !falls(mc, open.above())
                    && (where.getY() > cell.getY() || solid(mc, where.below()))) {
                opened.add(open.immutable());
                String failure = miner.begin(open);
                if (failure == null) {
                    Logbook.note("strip_mine", String.format("opening %d %d %d to "
                            + "reach %s", open.getX(), open.getY(), open.getZ(),
                            WeaponPicker.name(near.getItem())));
                    return;                      // re-evaluated next tick
                }
            }
            skippedObjects.add(near.getId());
            return;
        }
        if (here == null) {
            skippedObjects.add(near.getId());
            return;
        }
        Route.Result r = Route.search(world, here, overThere,
                new Route.Options(1, 800).withDeadline(15));
        if (!r.hasRoute() || walker.follow(r.steps(), x -> null, 0.6) != null) {
            skippedObjects.add(near.getId());
            return;
        }
        currentObject = near.getId();
        ticksInPhase = 0;
    }

    /**
     * Whether that item would fit in the backpack: a free slot, or a stack of the same
     * with room.
     */
    private static boolean fits(LocalPlayer p, net.minecraft.world.item.ItemStack stack) {
        var inv = p.getInventory();
        return inv.getFreeSlot() >= 0 || inv.getSlotWithRemainingSpace(stack) >= 0;
    }

    // --- the tunnel -----------------------------------------------------------------

    private void clear(Minecraft mc, LocalPlayer p) {
        if (!amAt(p, cell)) {
            returnDestination = cell;
            change(Phase.RETURN);
            return;
        }
        // Is the segment over? Only branches have a length.
        if (segmentLimit >= 0 && segmentProgress >= segmentLimit) {
            segmentFinished(null);
            return;
        }
        // Time for branches from this main tunnel tile?
        if (!inBranch && withBranches && mainProgress > 0
                && mainProgress % EVERY_BRANCHES == 0
                && branchesDoneAt != mainProgress) {
            branchesDoneAt = mainProgress;
            mainCell = cell;
            inBranch = true;
            branchSide = 0;
            currentDir = heading.getCounterClockWise();
            segmentLimit = BRANCH_LENGTH;
            segmentProgress = 0;
            Logbook.note("strip_mine", String.format("branch to the left (%s) "
                    + "from %d %d %d", name(currentDir), cell.getX(),
                    cell.getY(), cell.getZ()));
            return;
        }

        BlockPos feet = cell.relative(currentDir);
        BlockPos head = feet.above();
        BlockPos ground = feet.below();

        // Look before touching: lava or water next to what is about to be opened.
        for (BlockPos b : new BlockPos[]{head, feet}) {
            if (obstructs(mc, b) && liquidBeside(mc, b)) {
                segmentFinished(String.format("there is lava or water next to %d %d %d; "
                        + "I do not open there", b.getX(), b.getY(), b.getZ()));
                return;
            }
        }
        if (liquid(mc, feet) || liquid(mc, head) || liquid(mc, ground)) {
            segmentFinished(String.format("there is lava or water ahead (%d %d %d)",
                    feet.getX(), feet.getY(), feet.getZ()));
            return;
        }
        // The floor of the next step: if missing, it is covered with scaffolding and it
        // waits for the server to confirm (a click, ten ticks), trying several supports.
        // If there is no way, the segment ends there.
        if (!solid(mc, ground)) {
            if (groundTicks > 0 && --groundTicks > 0) return;
            if (groundAttempts >= 4) {
                groundAttempts = 0;
                segmentFinished(String.format("I could not cover the floor gap "
                        + "at %d %d %d", ground.getX(), ground.getY(), ground.getZ()));
                return;
            }
            int slot = Builder.slotWithScaffold(p);
            if (slot < 0) {
                groundAttempts = 0;
                segmentFinished(String.format("gap without ground at %d %d %d and I "
                        + "carry no blocks to cover it", ground.getX(),
                        ground.getY(), ground.getZ()));
                return;
            }
            Direction[] supports = {currentDir.getOpposite(), Direction.DOWN,
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
        // Top first and then bottom: if gravel falls, it falls before I pass.
        for (BlockPos b : new BlockPos[]{head, feet}) {
            if (!obstructs(mc, b)) continue;
            String failure = miner.begin(b);
            if (failure == null) return;             // digging; seen next tick
            if (failure.startsWith("I cannot see it")) {
                // Something got in front of me (gravel that fell into the tunnel): remove
                // it first, the strip is not cut because of it (otherwise gravel buried
                // it and the strip died with "there is gravel in the way").
                BlockPos obstruction = Miner.whatIsInTheWay(p, b);
                if (obstruction != null && obstructs(mc, obstruction)
                        && !liquidBeside(mc, obstruction)
                        && miner.begin(obstruction) == null) {
                    Logbook.note("strip_mine", String.format(
                            "removing %s blocking my way at %d %d %d",
                            Miner.nameOf(mc.level.getBlockState(obstruction)),
                            obstruction.getX(), obstruction.getY(), obstruction.getZ()));
                    return;
                }
            }
            if (failure.startsWith("I have no permission")
                    || failure.startsWith("I may break")) {
                finish(String.format("at %d %d %d: %s", b.getX(), b.getY(),
                        b.getZ(), failure));
                return;
            }
            segmentFinished(String.format("I could not open %d %d %d: %s", b.getX(),
                    b.getY(), b.getZ(), failure));
            return;
        }
        // Gap made: one step forward.
        String failure = walker.follow(List.of(point(cell), point(feet)),
                x -> null, 0.4);
        if (failure != null) {
            segmentFinished("I could not take the step: " + failure);
            return;
        }
        change(Phase.ADVANCE);
    }

    private void advance(Minecraft mc, LocalPlayer p) {
        if (walker.walking()) return;
        BlockPos feet = cell.relative(currentDir);
        if (amAt(p, feet)) {
            cell = feet;
            segmentProgress++;
            if (!inBranch) mainProgress++;
            advanceAttempts = 0;
            // What could not be seen from the previous tile may be seen from this one:
            // the skipped list is per tile, not per strip.
            skippedMinerals.clear();
            veinSteps = 0;
            opened.clear();
            change(Phase.MINERAL);
            return;
        }
        if (++advanceAttempts > 3) {
            segmentFinished(String.format("I cannot get into %d %d %d",
                    feet.getX(), feet.getY(), feet.getZ()));
            advanceAttempts = 0;
            return;
        }
        // Again, in case a push or a stumble left it half done.
        change(Phase.CLEAR);
    }

    private void goBack(Minecraft mc, LocalPlayer p) {
        if (walker.walking()) return;
        if (returnDestination == null || amAt(p, returnDestination)) {
            if (returnDestination != null && returnDestination.equals(mainCell)
                    && inBranch && segmentLimitReached()) {
                nextBranchOrMain();
            }
            returnDestination = null;
            change(Phase.CLEAR);
            return;
        }
        // The way-back tile blocked (gravel that fell into the tunnel): cleared if in
        // reach, instead of "it is not a spot where one can stand".
        for (BlockPos c : new BlockPos[]{returnDestination.above(), returnDestination}) {
            if (!obstructs(mc, c) || liquidBeside(mc, c)) continue;
            if (p.distanceToSqr(Vec3.atCenterOf(c)) > REACH * REACH) break;
            if (miner.begin(c) == null) {
                Logbook.note("strip_mine", String.format(
                        "the way-back tile %d %d %d is blocked with %s: clearing it",
                        c.getX(), c.getY(), c.getZ(),
                        Miner.nameOf(mc.level.getBlockState(c))));
                ticksInPhase = 0;
                return;
            }
        }
        ClientWorld world = new ClientWorld(mc.level);
        Route.Point here = MarionetteBot.whereAmI(world, p);
        if (here == null) {
            finish("I got lost: I do not know which tile I am on");
            return;
        }
        Route.Result r = Route.search(world, here, point(returnDestination),
                new Route.Options(MarionetteBot.safeFall(p.getHealth()), 4000)
                        .withDeadline(30));
        if (!r.hasRoute()) {
            // Following a vein it may end up in a hole it cannot walk out of: the usual
            // Rescue, climbing by placing blocks under the feet (only if enclosed and
            // there is a way out above).
            if (Rescue.inAHole(world, here)) {
                int tall = Rescue.exitHeight(world, here);
                if (tall > 0
                        && walker.follow(Rescue.staircase(here, tall), null) == null) {
                    Logbook.note("strip_mine", String.format("trapped at %d %d %d "
                            + "after the vein: climbing %d blocks to get back to the tunnel",
                            here.x(), here.y(), here.z(), tall));
                    ticksInPhase = 0;
                    return;
                }
            }
            finish(String.format("I find no way back to the tunnel "
                    + "(%d %d %d): %s", returnDestination.getX(),
                    returnDestination.getY(), returnDestination.getZ(), r.reason()));
            return;
        }
        String failure = walker.follow(r.steps(), x -> null, 0.4);
        if (failure != null) {
            finish("I could not get back to the tunnel: " + failure);
            return;
        }
        ticksInPhase = 0;
    }

    private boolean segmentLimitReached() {
        return segmentLimit < 0 || segmentProgress >= segmentLimit;
    }

    /**
     * A segment ended, by length or by a stumble. In a branch, it goes back to the main
     * tunnel tile; in the main tunnel (which has no length) only a stumble gets here, and
     * there the strip ends saying why.
     */
    private void segmentFinished(String reason) {
        if (inBranch) {
            if (reason != null) {
                Logbook.note("strip_mine", "I cut the branch short: " + reason);
            }
            // So that on arriving it moves to the next branch even if the length was not
            // reached.
            segmentProgress = Math.max(segmentProgress, segmentLimit);
            returnDestination = mainCell;
            change(Phase.RETURN);
            return;
        }
        finish(reason != null ? reason : "I finished the strip mine");
    }

    /** Stops by its own decision and notifies the brain, like the {@link StairDigger}. */
    private void finish(String because) {
        stop(because);
        Needs.warn("strip_mine:" + Integer.toHexString(because.hashCode()),
                "strip mine: " + because);
    }

    private void nextBranchOrMain() {
        if (branchSide == 0) {
            branchSide = 1;
            currentDir = heading.getClockWise();
            segmentLimit = BRANCH_LENGTH;
            segmentProgress = 0;
            cell = mainCell;
            Logbook.note("strip_mine", String.format("branch to the right (%s) "
                    + "from %d %d %d", name(currentDir), cell.getX(),
                    cell.getY(), cell.getZ()));
            return;
        }
        branchesDone++;
        inBranch = false;
        branchSide = 2;
        currentDir = heading;
        segmentLimit = -1;
        segmentProgress = mainProgress;
        cell = mainCell;
        Logbook.note("strip_mine", String.format("continuing the main tunnel to "
                + "the %s (%d blocks, %d minerals)", name(heading),
                mainProgress, minerals));
    }

    // --- plumbing -------------------------------------------------------------

    static boolean amAt(LocalPlayer p, BlockPos c) {
        double dx = p.getX() - (c.getX() + 0.5);
        double dz = p.getZ() - (c.getZ() + 0.5);
        return dx * dx + dz * dz < 0.36 && Math.abs(p.getY() - c.getY()) < 1.05;
    }

    static Route.Point point(BlockPos b) {
        return new Route.Point(b.getX(), b.getY(), b.getZ());
    }

    static Direction direction(String toward, LocalPlayer p) {
        if (toward == null || toward.isBlank()) return p.getDirection();
        return switch (toward.trim().toLowerCase()) {
            case "north" -> Direction.NORTH;
            case "south" -> Direction.SOUTH;
            case "east" -> Direction.EAST;
            case "west" -> Direction.WEST;
            default -> null;
        };
    }

    static String name(Direction d) {
        return switch (d) {
            case NORTH -> "north";
            case SOUTH -> "south";
            case EAST -> "east";
            case WEST -> "west";
            default -> d.getName();
        };
    }

    synchronized String state() {
        if (!mining) {
            return String.format("{\"mining\":false,\"outcome\":\"%s\"}",
                    Request.escape(outcome));
        }
        return String.format(
                "{\"mining\":true,\"heading\":\"%s\",\"phase\":\"%s\","
                + "\"in_branch\":%b,\"progress\":%d,\"branches\":%d,\"minerals\":%d,"
                + "\"following_vein\":%b,"
                + "\"cell\":{\"x\":%d,\"y\":%d,\"z\":%d}}",
                name(heading), phase.name().toLowerCase(), inBranch,
                mainProgress, branchesDone, minerals,
                phase == Phase.VEIN,
                cell.getX(), cell.getY(), cell.getZ());
    }
}
