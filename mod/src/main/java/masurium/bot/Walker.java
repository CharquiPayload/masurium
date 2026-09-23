package masurium.bot;

import masurium.common.Logbook;
import masurium.common.Request;
import masurium.common.Route;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.function.Function;

/**
 * Follows a route, point by point.
 * The route is computed by {@link Route} (pure logic, tested against text maps) and here
 * it is only executed: look at the next point, push forward, jump when needed, and move
 * to the next one on arrival.
 * That split is what allows testing behaviour without opening the game. What is down here
 * cannot be tested without Minecraft; what is in {@link Route} can, and that is where the
 * decisions live.
 *
 * <p><b>When it gets stuck it neither goes around in circles nor lies.</b> It vetoes the
 * tile it cannot get into ({@link StuckSpots}), replans from wherever it is (up to three
 * times, with a short detour if there is no route from here) and if that fails too, it
 * stops saying where it stayed and how much was left.
 */
final class Walker {

    /**
     * An intermediate point counts as reached within this. Asking for block exactness
     * would mean never arriving because of friction.
     */
    private static final double NEAR = 0.85;
    /** The last point gets more slack: it is "the destination", not a tile. */
    private static final double NEAR_END = 1.4;

    /**
     * The last point's slack IN THIS walk. Almost always NEAR_END; tightened to go after
     * something lying on the ground.
     */
    private double nearEnd = NEAR_END;

    private static final int STUCK_TICKS = 20;
    private static final double MIN_PROGRESS = 0.35;
    private static final int JUMPS_MAX = 6;

    private static final int TICKS_MAX = 20 * 90;

    /** Escaping: speed is worth more than control. See {@link #urgent(boolean)}. */
    private boolean urgent;

    private List<Route.Point> route;
    private int index;
    private Function<Route.Point, List<Route.Point>> replanner;
    /** How many times another route was requested in this walk. */
    private int replans;
    private static final int REPLANS_MAX = 3;
    /** Radius of the short detour tried when there is no route from here. */
    private static final int DETOUR_RADIUS = 5;
    /**
     * Whether the current route is a detour: when it ends, the real one is requested
     * again.
     */
    private boolean detour;

    private Input ownEntry;
    private Input originalEntry;

    private int ticks;
    private int jumps;
    private Vec3 lastPos;

    private String outcome = "I have not walked yet";
    private boolean walking;

    /**
     * The door it opened to pass and has not closed yet, or null. Closing it is the
     * important half: many creeper accidents happen because a door was left open.
     */
    private BlockPos doorOpen;
    /**
     * Courtesy ticks after pushing a door: the server confirms the change, and pressing
     * it again before that arrives would close it again.
     */
    private int doorWait;
    /**
     * Which side of the door's plane it was on when opening it (+1 or -1; 0 if unknown).
     * Close it only when on the OTHER side: otherwise it opened a door from two blocks
     * away and "closed it behind itself" in the same second, without having gone through,
     * and locked itself out.
     */
    private double doorSide;
    /**
     * How far past the door's center (along its axis) it must be to close it: almost a
     * block, with the whole body out of the door leaf. Closing on crossing the center
     * (the first version) closed it with the bot still half inside, the leaf pushed it
     * back into the doorway, and it opened again: 25 times a second, "trapped in a trance
     * at a door".
     */
    private static final double DOOR_PASS = 0.9;
    /**
     * From farther than this it does not press: let it keep walking towards it. Opening
     * it from four blocks away was opening it too early.
     */
    private static final double PRESS_DOOR = 2.3;

    /**
     * The block being dug to get through, or null. Telling it apart matters:
     * startDestroyBlock goes ONCE and continueDestroyBlock the rest, or the digging
     * progress resets by itself.
     */
    private BlockPos diggingAt;

    /**
     * Where the block of the tower in progress goes; null if there is none. The whole
     * column is stored because on jumping the current position changes and would stop
     * matching the spot where the block goes.
     */
    private Integer towerAtY;
    private int towerAtX;
    private int towerAtZ;
    private int towerTicks;
    private int blocksPlaced;

    /**
     * @param route    points to follow; the first one is where it already is
     * @param replanner called if it gets stuck, to ask for another route
     */
    synchronized String follow(List<Route.Point> route,
                               Function<Route.Point, List<Route.Point>> replanner) {
        return follow(route, replanner, NEAR_END);
    }

    /**
     * Whether the NEXT route handed over was planned by breaking blocks: they get broken
     * even if the global preference is off. Requested by whoever planned the route (the
     * Follower behind its boss); consumed on handing it over.
     */
    private boolean breaksNext;
    private boolean routeBreaks;

    synchronized void breakingTheNext(boolean yes) {
        breaksNext = yes;
    }

    /**
     * The same, but saying how close to get to the destination.
     *
     * <p>It exists because of items on the ground: with the usual slack (1.4) it counted
     * as arrived next to the log and the game never put it in the inventory, because for
     * picking up the boxes have to almost touch. Asking for exactness ALWAYS would be
     * worse (friction would mean never arriving anywhere), so it is only asked for when
     * needed.
     */
    synchronized String follow(List<Route.Point> route,
                               Function<Route.Point, List<Route.Point>> replanner,
                               double nearEnd) {
        this.nearEnd = nearEnd;
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return "I am in no world";
        if (!p.isAlive()) return "I am dead; I must respawn first";
        if (route == null || route.isEmpty()) return "I was given an empty route";

        this.route = route;
        this.routeBreaks = breaksNext;
        this.breaksNext = false;
        this.replanner = replanner;
        this.index = route.size() > 1 ? 1 : 0;   // the first one is where it already is
        this.replans = 0;
        this.detour = false;
        this.towerAtY = null;
        this.diggingAt = null;
        this.doorWait = 0;
        this.blocksPlaced = 0;
        ticks = 0;
        jumps = 0;
        lastPos = p.position();
        outcome = null;
        walking = true;
        installInput(p);
        return null;
    }

    synchronized void stop(String because) {
        // The outcome is the most useful line of the logbook: it says how it ended, not
        // how it started. Only noted if it was walking, or every repeated /stop would
        // clutter the log with the same thing.
        if (walking) Logbook.note("path", because);
        walking = false;
        outcome = because;
        LocalPlayer p = Minecraft.getInstance().player;
        closeOnStop(p);
        if (ownEntry != null) {
            ownEntry.forwardImpulse = 0;
            ownEntry.jumping = false;
        }
        if (p != null && originalEntry != null) p.input = originalEntry;
        // The sprint key does NOT stay pressed: like the use key, the client forgets that
        // nobody released it.
        Minecraft.getInstance().options.keySprint.setDown(false);
    }

    /**
     * Time to sprint?
     *
     * <p>Only on flat ground with flat ground ahead. The extra speed is paid in control,
     * and exactly where control is needed (a step, a one-block bridge, a tower, a
     * half-dug block) is where it does not sprint. Nor on the last segment: arriving
     * while braking is arriving closer.
     */
    private boolean canRun(LocalPlayer p, Route.Point goal) {
        if (towerAtY != null || diggingAt != null) return false;
        if (goal.y() > floorY(p)) return false;      // step
        if (index + 1 >= route.size()) {
            // Arriving while braking is arriving closer — which is the right trade
            // everywhere except when the thing behind is a creeper. Fleeing was walking
            // away from something that walks at the same speed: it never reached its
            // safe distance, gave up, and tried again. Three times, and then the anti-
            // loop gave up for it.
            return urgent;
        }
        return route.get(index + 1).y() == goal.y();            // and what comes after
    }

    /**
     * Run even where control would normally be worth more than speed.
     *
     * <p>Only for escaping. It does NOT lift the conditions that exist to avoid falling
     * or getting stuck (a step up, a tower, a half-dug block): sprinting off a ledge to
     * get away from a creeper is dying of the other thing.
     */
    synchronized void urgent(boolean yes) {
        urgent = yes;
    }

    /** One step. Called on every client tick, on the game thread. */
    synchronized void tick() {
        if (!walking) return;
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) { stop("I left the world halfway"); return; }
        if (!p.isAlive()) { stop("I was killed on the way"); return; }

        Route.Point goal = route.get(index);
        boolean lastOne = index == route.size() - 1;
        Vec3 center = new Vec3(goal.x() + 0.5, goal.y(), goal.z() + 0.5);
        double missing = horizontal(p.position(), center);
        // Mounted, what treads the ground is the horse: its height and its "I am on the
        // ground" are what count, not the bot sitting on top (a meter and a half up and
        // never touching ground). Without this, on horseback no point was ever reached
        // and the trip never started.
        net.minecraft.world.entity.Entity base = p.isPassenger() ? p.getVehicle() : p;
        double groundY = base.getY();

        // Arriving requires STANDING on the point, not flying past its height. In a tower
        // every point shares X and Z, so without this the jump counted them as reached
        // halfway up: the bot fell back, the pending point was two above, and the tower
        // branch (which only acts on the immediate step) never activated. It jumped in
        // place without placing a single block.
        boolean settled = base.onGround() || base.isInWater();
        if (missing <= (lastOne ? nearEnd : NEAR)
                && Math.abs(groundY - goal.y()) <= 0.5
                && settled) {
            if (lastOne) {
                if (detour) {
                    // It was a detour to get unstuck, not the destination: from here the
                    // real route is requested again.
                    detour = false;
                    replanFrom(p, "I finished the detour");
                    return;
                }
                stop(String.format("arrived (%.1f from the requested point)", missing));
                return;
            }
            index++;
            jumps = 0;
            return;
        }

        if (++ticks > TICKS_MAX) {
            stop(String.format("I ran out of time; I am at %s and I had "
                    + "%d points of %d left", where(p), route.size() - index, route.size()));
            return;
        }

        // Inside a berry bush it moves at a third of the speed and jumping is useless: it
        // is not stuck, it is slow. It keeps pushing and gets out in a few seconds.
        boolean inBush = Minecraft.getInstance().level != null
                && Minecraft.getInstance().level.getBlockState(p.blockPosition())
                        .is(net.minecraft.world.level.block.Blocks.SWEET_BERRY_BUSH);
        if (ticks % STUCK_TICKS == 0 && !inBush) {
            if (horizontal(p.position(), lastPos) < MIN_PROGRESS) {
                // THIS is the line that was missing the night of the tree. Repeated, it
                // collapses into one with its count, so it stands out: "no progress at
                // -894 70 906 (x6)" is a whole diagnosis.
                Logbook.note("stuck", String.format(
                        "no progress at %s, jumping", where(p)));
                if (++jumps > JUMPS_MAX && !tryReplan(p)) return;
            } else {
                jumps = 0;
            }
            lastPos = p.position();
        }

        // Is there a closed door ahead? It is opened before pushing against it, and
        // closed as soon as it has been crossed.
        closeIfDue(p);
        if (openIfNeeded(p, goal)) return;
        // Does this step have to be built? If so, it gets built and it stops pushing:
        // walking against a gap does not cross it.
        if (buildIfNeeded(p, goal)) return;
        // Or does it have to be dug? The break_to_advance toggle: the route may go
        // through whitelisted blocks, and here they really get broken.
        if (breakIfNeeded(p, goal)) return;

        double dx = center.x - p.getX();
        double dz = center.z - p.getZ();
        p.setYRot((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
        // And the head, LEVEL: here it only turns horizontally, so the pitch stayed
        // wherever the last dig left it, staring at the floor forever, which looked
        // terrifying. It decays smoothly instead of jumping to zero, and steps on nobody:
        // digging, placing or sleeping set their own look every time they act.
        p.setXRot(p.getXRot() * 0.7f);

        // SPRINT. The faked key again: the client only STARTS sprinting if it sees the
        // sprint key pressed (LocalPlayer.aiStep checks it), and in a bot it never is.
        // The game itself stops with hunger below 6, so that need not be checked here.
        Minecraft.getInstance().options.keySprint.setDown(canRun(p, goal));
        ownEntry.forwardImpulse = 1.0f;
        // Jump if stuck, or if the next point is higher: that solves a step without
        // calculating anything. In water, jumping is FLOATING: with the key pressed it
        // swims along the surface and gets out on the shore; without it, it sinks and
        // drowns. It is only released if the next point is lower (diving on purpose,
        // which the path finder charges dearly and rarely picks).
        if (p.isPassenger()) {
            // On horseback the jump key CHARGES the jump and fires on release: holding it
            // is never jumping. It is pressed in eight-tick bursts, and only if needed:
            // the horse climbs a one-block step by itself.
            boolean isNeeded = jumps > 0
                    || goal.y() > Math.floor(groundY) + 1
                    || (base.horizontalCollision && base.onGround());
            ownEntry.jumping = isNeeded && (ticks % 16) < 8;
        } else {
            ownEntry.jumping = jumps > 0
                    || goal.y() > floorY(p)
                    || (p.horizontalCollision && p.onGround())
                    || (p.isInWater() && goal.y() >= floorY(p));
        }
    }

    /**
     * Opens the door blocking the next step.
     *
     * <p>The route already counted what opening it costs; here it is really opened. It is
     * pressed ONCE and a few ticks are waited: the server confirms the change, and
     * pressing it again before the answer arrives would close it again, leaving the bot
     * opening and closing it in place.
     *
     * @return true if this tick went to the door (and there is no walking)
     */
    private boolean openIfNeeded(LocalPlayer p, Route.Point goal) {
        if (doorWait > 0) { doorWait--; return true; }
        Minecraft mc = Minecraft.getInstance();
        BlockPos feet = new BlockPos(goal.x(), goal.y(), goal.z());
        // Also the tile where it IS: if a door closed on it (or someone closed it), the
        // next step is no longer the door and without this it pushed against it without
        // opening it again.
        BlockPos me = p.blockPosition();
        BlockPos which = closed(mc, feet) ? feet
                : closed(mc, feet.above()) ? feet.above()
                : closed(mc, me) ? me
                : closed(mc, me.above()) ? me.above() : null;
        if (which == null) return false;
        // From far away it cannot reach to press: let it keep walking towards it.
        if (p.getEyePosition().distanceTo(Vec3.atCenterOf(which)) > PRESS_DOOR) {
            return false;
        }
        press(mc, p, which);
        doorOpen = which;
        doorSide = Math.signum(projection(p, mc, which));
        doorWait = 6;
        Logbook.note("door", String.format("opening the door at %d %d %d",
                which.getX(), which.getY(), which.getZ()));
        return true;
    }

    /**
     * Closes what it opened, as soon as it is out of the frame and still within reach. If
     * it got too far it is forgotten: going back for it would be leaving the path, and
     * nobody asked for that.
     */
    private void closeIfDue(LocalPlayer p) {
        if (doorOpen == null) return;
        Minecraft mc = Minecraft.getInstance();
        double d = p.getEyePosition().distanceTo(Vec3.atCenterOf(doorOpen));
        if (d > 4.0 || !handheld(mc, doorOpen)
                || closed(mc, doorOpen)) {
            doorOpen = null;               // far away, or no longer needed
            return;
        }
        if (d < 1.2) return;                    // still in the frame
        // Only when it has crossed ENTIRELY: past the center and almost a block beyond,
        // with the body out of where the leaf swings. Distance alone does not tell (at
        // 1.8 one is as far arriving as leaving), and just changing sides does not either
        // (see DOOR_PASS).
        double s = projection(p, mc, doorOpen);
        if (Math.signum(s) == doorSide || Math.abs(s) < DOOR_PASS) return;
        press(mc, p, doorOpen);
        Logbook.note("door", "closing it behind me");
        doorOpen = null;
    }

    /**
     * If it stops walking with a door opened by hand, it closes it, whichever side it is
     * on, as long as it is not in the frame (closing it on itself would trap it in the
     * leaf).
     */
    private void closeOnStop(LocalPlayer p) {
        if (doorOpen == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (p != null && mc.level != null) {
            double d = p.getEyePosition().distanceTo(Vec3.atCenterOf(doorOpen));
            if (d >= 1.2 && d <= 4.0 && handheld(mc, doorOpen)
                    && !closed(mc, doorOpen)) {
                press(mc, p, doorOpen);
                Logbook.note("door", "closing it on stopping");
            }
        }
        doorOpen = null;
    }

    /**
     * Where it is relative to the door, along the axis crossing it (the FACING of doors
     * and gates): negative on one side, positive on the other, in blocks from the center.
     * 0 if the door does not say where it faces. Going through changes the sign; being
     * out of the leaf also means a large value.
     */
    private static double projection(LocalPlayer p, Minecraft mc, BlockPos door) {
        var state = mc.level.getBlockState(door);
        if (!state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) return 0;
        Direction f = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
        Vec3 c = Vec3.atCenterOf(door);
        return (p.getX() - c.x) * f.getStepX() + (p.getZ() - c.z) * f.getStepZ();
    }

    private static void press(Minecraft mc, LocalPlayer p, BlockPos where) {
        Vec3 center = Vec3.atCenterOf(where);
        p.lookAt(EntityAnchorArgument.Anchor.EYES, center);
        mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND,
                new BlockHitResult(center, Direction.UP, where, false));
        p.swing(InteractionHand.MAIN_HAND);
    }

    /** A door or gate opened by hand (iron ones do not count). */
    private static boolean handheld(Minecraft mc, BlockPos where) {
        var state = mc.level.getBlockState(where);
        return state.is(BlockTags.WOODEN_DOORS)
                || state.is(BlockTags.FENCE_GATES);
    }

    /** A hand-opened door that is also CLOSED. */
    private static boolean closed(Minecraft mc, BlockPos where) {
        if (!handheld(mc, where)) return false;
        var state = mc.level.getBlockState(where);
        return state.hasProperty(BlockStateProperties.OPEN)
                && !state.getValue(BlockStateProperties.OPEN);
    }

    /**
     * Places the block missing to take the next step.
     *
     * @return true if this tick went to building (and there is no walking)
     */
    private boolean buildIfNeeded(LocalPlayer p, Route.Point goal) {
        Minecraft mc = Minecraft.getInstance();
        int px = (int) Math.floor(p.getX());
        int pz = (int) Math.floor(p.getZ());
        int py = floorY(p);

        // --- tower in progress --- It goes BEFORE deciding whether to start one, and
        // does not look at the current height again: when jumping that height rises and
        // the condition "the point is right above" stops holding mid-jump. Re-evaluating
        // it aborted the tower in the air and the bot jumped in place forever. A started
        // tower is a STATE, not a condition.
        if (towerAtY != null) {
            ownEntry.forwardImpulse = 0;
            ownEntry.jumping = true;
            // The block is NOT placed at take-off. While going up, the body itself
            // occupies the spot where it goes, and the server rejects the placement
            // silently. It has to wait for the top of the jump.
            if (p.getY() > towerAtY + 1.1) {
                String failure = Builder.place(
                        new BlockPos(towerAtX, towerAtY, towerAtZ), Direction.DOWN);
                if (failure != null) { stop("I could not build a tower: " + failure); return true; }
                blocksPlaced++;
                Logbook.note("tower", "block placed to climb");
                towerAtY = null;
            } else if (++towerTicks > 40) {
                stop("I tried to build a tower and could not get off the ground");
                return true;
            }
            return true;
        }

        // --- start a tower: the next point is right above ---
        if (goal.x() == px && goal.z() == pz && goal.y() == py + 1
                && p.onGround()) {
            towerAtY = py;
            towerAtX = px;
            towerAtZ = pz;
            towerTicks = 0;
            ownEntry.forwardImpulse = 0;
            ownEntry.jumping = true;
            return true;
        }

        // --- bridge: the next point is beside and has no floor --- ...unless the "no
        // floor" is WATER: there it swims. Water has no collision box, so every step of a
        // swimming route (which the path finder DOES pick: swimming costs 9 and bridging
        // 23) used to turn into a bridge here: a bot spent 34 cobblestone in a lake and
        // without cobblestone gave up with "I could not bridge". It can simply move
        // through the water and float.
        boolean sameLevel = goal.y() == py;
        BlockPos belowGoal = new BlockPos(goal.x(), goal.y() - 1, goal.z());
        BlockPos atGoal = new BlockPos(goal.x(), goal.y(), goal.z());
        boolean swimming = mc.level != null
                && (!mc.level.getFluidState(atGoal).isEmpty()
                    || !mc.level.getFluidState(belowGoal).isEmpty());
        if (sameLevel && !swimming && mc.level != null
                && mc.level.getBlockState(belowGoal).getCollisionShape(
                        mc.level, belowGoal).isEmpty()) {
            Direction toward = Builder.towardWhere(goal.x() - px, goal.z() - pz);
            if (toward == null) return false;      // diagonal: no bridging
            ownEntry.forwardImpulse = 0;
            ownEntry.jumping = false;
            String failure = Builder.place(belowGoal, toward.getOpposite());
            if (failure != null) { stop("I could not bridge: " + failure); return true; }
            blocksPlaced++;
            Logbook.note("bridge", "block placed to cross");
            return true;
        }
        return false;
    }

    /**
     * Digs the block blocking the next step, if the toggle allows it.
     *
     * <p>The miner's same lock applies here AT the moment of breaking: the route already
     * checked the whitelist when planning, but the world may have changed in between, and
     * a lock that only checks the plan is no lock. Top to bottom (head then feet), like a
     * demolition.
     *
     * @return true if this tick went to digging (and there is no walking)
     */
    /**
     * The tile where the feet are, which is NOT floor(y) on a partial block: on a dirt
     * path or farmland (15/16), soul sand or mud (7/8) the feet are at 63.94 and floor
     * gives 63, the block's own cell; the route counts that tile as 64. Every step looked
     * like a stair and the bot hopped along paths, trampling crops. If the block in the
     * feet cell is taller than half and it is standing on it, the tile is the one above:
     * the same criterion ClientWorld uses for "solid".
     */
    static int floorY(LocalPlayer p) {
        int y = (int) Math.floor(p.getY());
        var levelValue = p.level();
        BlockPos b = BlockPos.containing(p.getX(), y, p.getZ());
        var box = levelValue.getBlockState(b).getCollisionShape(levelValue, b);
        if (!box.isEmpty()) {
            double cap = box.max(Direction.Axis.Y);
            if (cap > 0.5 && p.getY() >= y + cap - 0.02) return y + 1;
        }
        return y;
    }

    private boolean breakIfNeeded(LocalPlayer p, Route.Point goal) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return false;
        if (goal.y() != floorY(p)) return false;
        BlockPos feet = new BlockPos(goal.x(), goal.y(), goal.z());
        BlockPos head = feet.above();
        BlockPos plug =
                !mc.level.getBlockState(head)
                        .getCollisionShape(mc.level, head).isEmpty() ? head
                : !mc.level.getBlockState(feet)
                        .getCollisionShape(mc.level, feet).isEmpty() ? feet
                : null;
        if (plug == null) {
            diggingAt = null;
            return false;
        }
        // The global preference, or that THIS route was planned breaking: a route that
        // goes through blocks and then does not break them is going in circles.
        if (!routeBreaks && !Preferences.is("break_to_advance")) return false;
        var state = mc.level.getBlockState(plug);
        String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(state.getBlock()).getPath();
        if (!BreakPermissions.mayIBreak(id)) {
            stop("the way is blocked by " + id
                    + " and I have no permission to break it");
            return true;
        }
        ownEntry.forwardImpulse = 0;
        ownEntry.jumping = false;
        int slot = Miner.bestTool(p, state);
        // bestTool only looks at the HOTBAR: with the pickaxe in the backpack and a
        // cobblestone in hand, it dug the stone with the cobblestone. The backpack first,
        // as the Miner does.
        if (state.requiresCorrectToolForDrops()
                && !p.getInventory().getItem(slot).isCorrectToolForDrops(state)) {
            String inBag = Miner.toolInBackpack(p, state);
            int ascent = inBag == null ? -1 : MasuriumBot.takeFromBackpack(p, inBag);
            if (ascent >= 0) slot = ascent;
        }
        if (p.getInventory().selected != slot) {
            p.getInventory().selected = slot;
            return true;   // the usual lost tick
        }
        p.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument
                .Anchor.EYES, Vec3.atCenterOf(plug));
        Direction face = Direction.getNearest(
                p.getX() - (plug.getX() + 0.5),
                p.getEyeY() - (plug.getY() + 0.5),
                p.getZ() - (plug.getZ() + 0.5));
        if (!plug.equals(diggingAt)) {
            diggingAt = plug;
            Logbook.note("path", "digging " + id + " to get through");
            mc.gameMode.startDestroyBlock(plug, face);
        } else {
            mc.gameMode.continueDestroyBlock(plug, face);
        }
        p.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        return true;
    }

    /**
     * Really stuck (six jumps without progress): look for another route.
     *
     * <p>It used to replan ONCE from the same spot, and the path finder returned the same
     * route: for it that tile was still walkable. Now the tile it cannot get into is
     * vetoed for a while for every path finder ({@link StuckSpots}), another route is
     * requested, and if there is none from here a short detour to a nearby tile is tried
     * and the route is requested again from there. Up to {@value #REPLANS_MAX} times;
     * after that, it stops saying where and how much was left.
     *
     * @return true if it got another route (or a detour) and keeps walking
     */
    private boolean tryReplan(LocalPlayer p) {
        if (replanner == null) {
            stop(String.format("I got stuck at %s; I had %d points of %d left "
                    + "and jumping did not solve it", where(p), route.size() - index,
                    route.size()));
            return false;
        }
        Route.Point goal = route.get(index);
        StuckSpots.markPlace(goal);
        Logbook.note("path", String.format(
                "I got stuck at %s going to %d %d %d; I veto that tile and "
                + "replan (%d of %d)", where(p), goal.x(), goal.y(), goal.z(),
                replans + 1, REPLANS_MAX));
        return replanFrom(p, "stuck");
    }

    private boolean replanFrom(LocalPlayer p, String because) {
        if (++replans > REPLANS_MAX) {
            stop(String.format("I got stuck at %s; I had %d points of %d left "
                    + "and neither jumping nor %d replans solved it",
                    where(p), route.size() - index, route.size(),
                    REPLANS_MAX));
            return false;
        }
        Route.Point here = new Route.Point(
                (int) Math.floor(p.getX()), floorY(p),
                (int) Math.floor(p.getZ()));
        List<Route.Point> another = replanner.apply(here);
        if (another != null && another.size() >= 2) {
            route = another;
            index = 1;
            jumps = 0;
            ticks = 0;
            lastPos = p.position();
            return true;
        }
        // No route from here. A single step (a two-point route) does not deserve a
        // detour: whoever asked already knows what to do. A trip does.
        List<Route.Point> goAround = route.size() > 2 ? detour(p, here) : null;
        if (goAround == null) {
            stop(String.format("I got stuck at %s and no longer find a route to the "
                    + "destination%s", where(p),
                    route.size() > 2 ? ", nor a detour to try from" : ""));
            return false;
        }
        Route.Point end = goAround.get(goAround.size() - 1);
        Logbook.note("path", String.format("no route from here (%s); I "
                + "detour to %d %d %d to try from there", because, end.x(),
                end.y(), end.z()));
        route = goAround;
        index = 1;
        jumps = 0;
        ticks = 0;
        detour = true;
        lastPos = p.position();
        return true;
    }

    /**
     * A short detour: a walkable tile within {@value #DETOUR_RADIUS} blocks, farthest
     * first (one block away gets it out of nothing), with a short route and permission to
     * build. Few tries: this runs on the game thread and searching routes is what costs.
     */
    private List<Route.Point> detour(LocalPlayer p, Route.Point here) {
        ClientWorld m = new ClientWorld(Minecraft.getInstance().level);
        List<Route.Point> candidateSet = new java.util.ArrayList<>();
        for (int dx = -DETOUR_RADIUS; dx <= DETOUR_RADIUS; dx++) {
            for (int dz = -DETOUR_RADIUS; dz <= DETOUR_RADIUS; dz++) {
                if (dx * dx + dz * dz < 4) continue;
                for (int dy = -1; dy <= 1; dy++) {
                    int x = here.x() + dx, y = here.y() + dy, z = here.z() + dz;
                    if (m.canStand(x, y, z)) candidateSet.add(new Route.Point(x, y, z));
                }
            }
        }
        candidateSet.sort((a, b) -> Integer.compare(
                (b.x() - here.x()) * (b.x() - here.x()) + (b.z() - here.z()) * (b.z() - here.z()),
                (a.x() - here.x()) * (a.x() - here.x()) + (a.z() - here.z()) * (a.z() - here.z())));
        int tried = 0;
        for (Route.Point c : candidateSet) {
            if (tried++ >= 6) break;
            Route.Result r = Route.search(m, here, c,
                    new Route.Options(MasuriumBot.safeFall(p.getHealth()), 1500, true)
                            .withDeadline(15));
            if (r.hasRoute() && r.steps().size() >= 2) return r.steps();
        }
        return null;
    }

    /**
     * HOW the last walk ended, as the state tells it. The Traveler reads it to say WHY a
     * segment got stuck.
     */
    synchronized String outcome() {
        return outcome;
    }

    synchronized String state() {
        if (!walking) {
            return String.format(
                    "{\"walking\":false,\"outcome\":\"%s\","
                    + "\"blocks_placed\":%d}",
                    Request.escape(outcome), blocksPlaced);
        }
        LocalPlayer p = Minecraft.getInstance().player;
        Route.Point goal = route.get(route.size() - 1);
        Route.Point next = route.get(index);
        double missing = p == null ? -1 : horizontal(p.position(),
                new Vec3(goal.x() + 0.5, goal.y(), goal.z() + 0.5));
        return String.format(
                "{\"walking\":true,\"destination\":{\"x\":%d,\"y\":%d,\"z\":%d},"
                + "\"next\":{\"x\":%d,\"y\":%d,\"z\":%d},"
                + "\"point\":%d,\"of\":%d,\"missing\":%.1f,\"jumps\":%d,"
                + "\"replanned\":%b,\"blocks_placed\":%d}",
                goal.x(), goal.y(), goal.z(),
                next.x(), next.y(), next.z(),
                index + 1, route.size(), missing, jumps, replans > 0,
                blocksPlaced);
    }

    synchronized boolean walking() {
        return walking;
    }

    /**
     * The player's input is replaced by our own.
     * It is needed because {@code Input.tick()} rewrites the impulses from the keyboard
     * every tick: setting the fields from outside does not survive a single tick. There
     * is no keyboard here, so nothing is lost.
     */
    private void installInput(LocalPlayer p) {
        if (ownEntry == null) {
            ownEntry = new Input() {
                @Override
                public void tick(boolean crouching, float bonus) {
                    // Empty on purpose: the impulses are set by tick(), above.
                }
            };
        }
        if (p.input != ownEntry) {
            originalEntry = p.input;
            p.input = ownEntry;
        }
    }

    private static double horizontal(Vec3 a, Vec3 b) {
        double dx = a.x - b.x, dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static String where(LocalPlayer p) {
        return String.format("%.0f %.0f %.0f", p.getX(), p.getY(), p.getZ());
    }
}
