package masurium.server.puppet;

import masurium.common.Route;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Clearing a box: every block in it broken, a layer at a time from the top, as a player
 * breaks them: from within reach, at what a click there would hit, with the best tool
 * it carries and the time the block takes with it. EXPERIMENT (server-side bots).
 *
 * <p>The breaking is the server's own for a player ({@code handleBlockBreakAction}: start,
 * then stop once the progress is complete), so protections, events and the tool's wear
 * apply as they would to anyone. What falls is picked up by walking over it, as a player
 * does, while there is room. Several puppets told to clear the same box share it: each
 * claims the block it goes for, and nobody goes down a layer before the one above is
 * done; but a block of the box in the way of the one it goes for is broken first,
 * whatever its layer.
 */
final class Clear extends Job {

    /** A route to a block ends where the eyes are this close to its centre (a player reaches 4.5). */
    private static final double REACH = 4.0;
    /** Where the eyes are over the feet. */
    private static final double EYES = 1.62;
    /** A block it walked to this many times and could not hit is given up. */
    private static final int TRIES_MAX = 3;
    /** Breaking one block for longer than this (ticks) is a refusal: it gives it up. */
    private static final int BREAK_MAX = 20 * 30;

    /** The box, shared by every puppet told to clear it together. */
    static final class Area {
        final ServerLevel level;
        final BlockPos min, max;
        /** The layer being cleared, and what is left in it. */
        int layer;
        final List<BlockPos> todo = new ArrayList<>();
        final Map<BlockPos, Puppets.Puppet> claimed = new HashMap<>();
        /** Blocks nobody could reach or break: given up. */
        final Set<BlockPos> given = new HashSet<>();
        int broken;
        boolean done;
        /** The layer on which what was left hidden was looked at again, once. */
        private int lookedAgain = Integer.MIN_VALUE;

        Area(ServerLevel level, BlockPos a, BlockPos b) {
            this.level = level;
            this.min = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
            this.max = new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
            this.layer = max.getY() + 1;
        }

        String box() {
            return min.toShortString() + " to " + max.toShortString();
        }

        boolean contains(BlockPos pos) {
            return pos.getX() >= min.getX() && pos.getX() <= max.getX() && pos.getY() >= min.getY()
                    && pos.getY() <= max.getY() && pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ();
        }

        /** Something to break: not air, not a liquid, not unbreakable (bedrock, barriers). */
        static boolean breakable(ServerLevel level, BlockPos pos) {
            BlockState s = level.getBlockState(pos);
            return !s.isAir() && !(s.getBlock() instanceof LiquidBlock) && s.getDestroySpeed(level, pos) >= 0;
        }

        /**
         * The nearest free block of the layer, claimed for {@code p}; null when there is
         * none for now (the others still work on this layer) or at all ({@link #done}).
         */
        BlockPos next(Puppets.Puppet p, Set<BlockPos> hidden) {
            // A claim of a puppet no longer at it is free again.
            claimed.entrySet().removeIf(e -> !working(e.getValue()));
            while (!done) {
                BlockPos best = null;
                double bestD = Double.MAX_VALUE;
                boolean anyHidden = false;
                for (BlockPos pos : todo) {
                    if (claimed.containsKey(pos) || given.contains(pos)) continue;
                    if (hidden.contains(pos)) {
                        anyHidden = true;
                        continue;
                    }
                    double d = pos.distToCenterSqr(p.body.position());
                    if (d < bestD) {
                        bestD = d;
                        best = pos;
                    }
                }
                if (best != null) {
                    if (!breakable(level, best)) {
                        todo.remove(best);
                        continue;
                    }
                    claimed.put(best, p);
                    return best;
                }
                if (!claimed.isEmpty()) return null;      // the layer's last blocks are someone's
                if (anyHidden && lookedAgain != layer) {
                    // Only blocks it could not see are left, and nobody is at work to
                    // uncover them: it looks again, once; then they are given up.
                    lookedAgain = layer;
                    hidden.clear();
                    continue;
                }
                for (BlockPos pos : todo) if (breakable(level, pos)) given.add(pos);
                todo.clear();                             // what is left was given up
                if (!nextLayer()) done = true;
            }
            return null;
        }

        /**
         * Takes {@code pos} for {@code p}, if it is in the box, still to break, and nobody
         * has it. Any layer: a block of the box that hides the one it goes for is broken
         * first, as a player would, and from below that is how it sees further in.
         */
        boolean claim(BlockPos pos, Puppets.Puppet p) {
            if (!contains(pos) || given.contains(pos) || !breakable(level, pos)) return false;
            Puppets.Puppet other = claimed.get(pos);
            if (other != null && other != p && working(other)) return false;
            claimed.put(pos, p);
            return true;
        }

        void release(BlockPos pos, Puppets.Puppet p) {
            if (pos != null && claimed.get(pos) == p) claimed.remove(pos);
        }

        void giveUp(BlockPos pos, Puppets.Puppet p) {
            release(pos, p);
            given.add(pos);
        }

        private boolean working(Puppets.Puppet q) {
            return q.body.isAlive() && !q.body.isRemoved() && q.job instanceof Clear c && c.area == this;
        }

        private boolean nextLayer() {
            while (--layer >= min.getY()) {
                for (int x = min.getX(); x <= max.getX(); x++) {
                    for (int z = min.getZ(); z <= max.getZ(); z++) {
                        BlockPos pos = new BlockPos(x, layer, z);
                        if (breakable(level, pos)) todo.add(pos);
                    }
                }
                if (!todo.isEmpty()) return true;
            }
            return false;
        }
    }

    final Area area;
    private BlockPos goal;
    private int tries;
    private BlockPos breaking;
    private Direction face = Direction.UP;
    private int breakTicks;
    private int broken;
    private boolean waiting;
    /**
     * Blocks of this layer it stood in reach of and could not see (another block, maybe
     * someone else's, in front): left for later, not given up.
     */
    private final Set<BlockPos> hidden = new HashSet<>();
    private int hiddenLayer;

    Clear(Area area) {
        this.area = area;
    }

    private String doing() {
        return "clearing " + area.box() + ": layer y=" + area.layer + ", " + broken + " broken by it, "
                + area.broken + " in all";
    }

    @Override
    String status() {
        String at = breaking != null ? " [breaking " + breaking.toShortString() + ", tick " + breakTicks + "]"
                : goal != null ? " [going for " + goal.toShortString() + ", try " + tries + "]" : "";
        return (waiting ? "clearing " + area.box() + ": waiting for the others to finish layer y=" + area.layer : doing())
                + at + (hidden.isEmpty() ? "" : " (" + hidden.size() + " hidden)")
                + (area.given.isEmpty() ? "" : " (" + area.given.size() + " given up)");
    }

    @Override
    boolean think(Puppets.Puppet p, long now) {
        if (p.body.level() != area.level) {
            Puppets.halt(p, "the box to clear is in another dimension");
            return false;
        }
        if (breaking != null) return true;
        if (goal != null && !Area.breakable(area.level, goal)) {
            area.release(goal, p);
            goal = null;
        }
        if (hiddenLayer != area.layer) {
            hidden.clear();
            hiddenLayer = area.layer;
        }
        if (goal == null) {
            goal = area.next(p, hidden);
            if (goal == null) {
                if (area.done) {
                    Puppets.halt(p, "cleared " + area.box() + ": " + broken + " broken by it, " + area.broken
                            + " in all" + (area.given.isEmpty() ? "" : ", " + area.given.size() + " given up"));
                    return false;
                }
                if (p.path != null) Puppets.halt(p, doing());
                waiting = true;
                return true;
            }
            waiting = false;
            tries = 0;
        }
        // What a click at it would hit from here, if anything is in reach: something of
        // the box in front of it is broken first.
        BlockHitResult hit = sight(p, goal);
        if (hit != null) {
            BlockPos seen = hit.getBlockPos();
            if (!seen.equals(goal) && area.claim(seen, p)) {
                area.release(goal, p);
                goal = seen;
            }
            if (seen.equals(goal)) {
                if (p.path != null) Puppets.halt(p, doing());
                start(p, hit.getDirection());
                return true;
            }
            // In reach and hidden behind something it may not break (outside the box, or
            // another puppet's): another block for now, this one later.
            if (p.path == null && p.pending == null) {
                hidden.add(goal);
                area.release(goal, p);
                goal = null;
                return true;
            }
        }
        if (p.pending != null || p.path != null) return true;
        if (now - p.plannedAt < Puppets.REPLAN_TICKS) return true;
        if (++tries > TRIES_MAX) {
            area.giveUp(goal, p);
            goal = null;
            return true;
        }
        p.plannedAt = now;
        BlockPos to = goal;
        Puppets.plan(p, to, world -> reachOf(to), doing());
        return true;
    }

    /**
     * Any tile from which the eyes are within {@link #REACH} of the block's centre. The
     * ring the path finder has for chasing weighs height at half, and ended routes on a
     * pit's rim four blocks over the block, out of reach; its estimate still serves,
     * since this goal lies inside that ring.
     */
    private static Route.Meta reachOf(BlockPos pos) {
        Route.Meta ring = Route.Meta.near(new Route.Point(pos.getX(), pos.getY(), pos.getZ()), REACH);
        double cx = pos.getX() + 0.5, cy = pos.getY() + 0.5, cz = pos.getZ() + 0.5;
        return new Route.Meta() {
            public boolean isGoal(int x, int y, int z) {
                double dx = x + 0.5 - cx, dy = y + EYES - cy, dz = z + 0.5 - cz;
                return dx * dx + dy * dy + dz * dz <= REACH * REACH;
            }

            public double heuristic(int x, int y, int z) {
                return ring.heuristic(x, y, z);
            }
        };
    }

    @Override
    void act(Puppets.Puppet p) {
        if (breaking == null) return;
        PuppetPlayer b = p.body;
        BlockState s = area.level.getBlockState(breaking);
        if (!Area.breakable(area.level, breaking)) {           // broken
            broken++;
            area.broken++;
            area.release(breaking, p);
            area.todo.remove(breaking);
            breaking = null;
            goal = null;
            return;
        }
        if (!b.canInteractWithBlock(breaking, 1.0) || ++breakTicks > BREAK_MAX) {
            // Pushed away, or the server will not let it: the stroke is dropped.
            action(b, breaking, ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK);
            if (breakTicks > BREAK_MAX) {
                area.giveUp(breaking, p);
                goal = null;
            }
            breaking = null;
            return;
        }
        Puppets.release(b);
        b.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(breaking));
        if (breakTicks % 4 == 0) b.swing(InteractionHand.MAIN_HAND);
        if (s.getDestroyProgress(b, area.level, breaking) * breakTicks >= 1.0f) {
            action(b, breaking, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK);
        }
    }

    @Override
    void end(Puppets.Puppet p) {
        if (breaking != null) action(p.body, breaking, ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK);
        area.release(breaking, p);
        area.release(goal, p);
        breaking = null;
        goal = null;
    }

    /** The first stroke: the best tool for it in hand, looking at it, and the server told. */
    private void start(Puppets.Puppet p, Direction side) {
        PuppetPlayer b = p.body;
        BlockState s = area.level.getBlockState(goal);
        hold(p, stack -> stack.getDestroySpeed(s) + (stack.isCorrectToolForDrops(s) ? 0.5 : 0));
        Puppets.release(b);
        b.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(goal));
        breaking = goal;
        face = side;
        breakTicks = 0;
        b.swing(InteractionHand.MAIN_HAND);
        action(b, breaking, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK);
    }

    /** What a client's packet would tell the server's game mode. */
    private void action(PuppetPlayer b, BlockPos pos, ServerboundPlayerActionPacket.Action what) {
        b.gameMode.handleBlockBreakAction(pos, what, face, area.level.getMaxBuildHeight(), 0);
    }

    /**
     * What a click at {@code pos} would hit from where it stands: the first block on the
     * line from its eyes, if within a player's reach; null if out of reach.
     */
    private BlockHitResult sight(Puppets.Puppet p, BlockPos pos) {
        PuppetPlayer b = p.body;
        if (!b.canInteractWithBlock(pos, 0.0)) return null;
        Vec3 eye = b.getEyePosition();
        Vec3 centre = Vec3.atCenterOf(pos);
        // A player hits a block wherever a bit of it shows: its centre, else the middle of
        // a face turned to the eyes.
        BlockHitResult first = null;
        for (int i = -1; i < 6; i++) {
            Vec3 aim = centre;
            if (i >= 0) {
                Direction d = Direction.from3DDataValue(i);
                aim = centre.add(d.getStepX() * 0.45, d.getStepY() * 0.45, d.getStepZ() * 0.45);
                if (aim.subtract(centre).dot(eye.subtract(centre)) <= 0) continue;     // turned away
            }
            BlockHitResult hit = area.level.clip(new ClipContext(eye, aim,
                    ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, b));
            if (hit.getType() != HitResult.Type.BLOCK) {
                return new BlockHitResult(aim, Direction.getNearest(eye.subtract(centre)), pos, false);
            }
            if (hit.getBlockPos().equals(pos)) return hit;
            if (first == null) first = hit;
        }
        return first != null && b.canInteractWithBlock(first.getBlockPos(), 0.0) ? first : null;
    }
}
