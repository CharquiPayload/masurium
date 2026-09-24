package masurium.server.puppet;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;
import masurium.common.Route;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

/**
 * Bots that live on the server instead of in a client: EXPERIMENT (branch
 * experiment/server-bots), to measure what docs/architecture.md discarded them for, the
 * cost on the server's tick.
 *
 * <p>A puppet is a {@link PuppetPlayer} let in through the door a joining player uses
 * (it is in TAB, it is seen, it loads chunks), with a connection that goes nowhere. Its
 * body is a player's, with a player's physics, and it walks by pressing a player's keys
 * along the routes of the same path finder the client bots use, the way their
 * {@code Walker} does. The route is searched on a thread of its own over the loaded
 * chunks, so that what the tick pays is the body and a look at where it goes.
 * {@code /masurium puppet stats} says what that costs, next to {@code /tick query}.
 *
 * <pre>
 *   /masurium puppet spawn &lt;name&gt;           a puppet where you stand
 *   /masurium puppet goto &lt;name&gt; &lt;x y z&gt;    walks there
 *   /masurium puppet follow &lt;name&gt; &lt;player&gt; walks after them
 *   /masurium puppet stop &lt;name&gt;            stands still
 *   /masurium puppet remove &lt;name&gt;          leaves
 *   /masurium puppet list | stats [reset]
 * </pre>
 */
public final class Puppets {

    private static final Logger LOG = LogUtils.getLogger();
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");

    // The client Walker's numbers: they are tuned against a player's physics, which is
    // what a puppet has.
    /** An intermediate point counts as reached within this; the last one, within more. */
    private static final double NEAR = 0.85, NEAR_END = 1.4;
    /** Every this many ticks, less progress than this is being stuck: it jumps. */
    private static final int STUCK_TICKS = 20;
    private static final double MIN_PROGRESS = 0.35;
    /** After this many jumps without progress it searches again, this many times. */
    private static final int JUMPS_MAX = 6, REPLANS_MAX = 3;
    private static final int TICKS_MAX = 20 * 90;
    /** A door is pressed from this close, and closed when this far past it. */
    private static final double PRESS_DOOR = 2.3, DOOR_PASS = 0.9;

    /** A follower plans again this often, and stops this close to whom it follows. */
    private static final int REPLAN_TICKS = 20;
    private static final double FOLLOW_GAP = 2.5;
    /** The chunks read around a search: this margin, at most these many. */
    private static final int MARGIN = 24, MAX_CHUNKS = 400;
    /** A search's budget on its own thread; past it, the stretch found is walked. */
    private static final long SEARCH_MS = 2000;

    private static final Map<String, Puppet> ALL = new LinkedHashMap<>();
    private static final ExecutorService ROUTES = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "masurium-puppet-routes");
        t.setDaemon(true);
        return t;
    });

    /** What the puppets cost the server's thread, per tick, and what their searches took. */
    private static final Stats STATS = new Stats();

    private static final class Puppet {
        final PuppetPlayer body;
        String doing = "standing";

        // What it walks, and what for.
        List<Route.Point> path;
        int next;
        boolean partial;
        Future<Route.Result> pending;
        /** Where a goto goes: a partial route is walked and searched again from its end. */
        BlockPos target;
        ServerPlayer following;
        long plannedAt = -REPLAN_TICKS;

        // How the walk goes.
        int ticks, jumps, replans;
        Vec3 lastPos;
        /** The door it opened and has not closed yet, and which side of it it was on. */
        BlockPos doorOpen;
        double doorSide;

        Puppet(PuppetPlayer body) {
            this.body = body;
        }

        String name() {
            return body.getGameProfile().getName();
        }
    }

    private static final class Stats {
        long ticks, nanos, maxNanos, bodyTicks;
        long snapshots, snapshotNanos, maxSnapshotNanos;
        long searches, searchMs, maxSearchMs, nodes;
        /** What the puppets' bodies took in the tick running now. */
        long thisTick;

        synchronized void search(long ms, int looked) {
            searches++;
            searchMs += ms;
            maxSearchMs = Math.max(maxSearchMs, ms);
            nodes += looked;
        }

        synchronized String text(int puppets) {
            return String.format(Locale.ROOT,
                    "%d puppet(s). On the server's thread: %.3f ms a tick for all of them on average,"
                            + " %.3f at most, over %d ticks (%.3f ms a puppet); of that, %d route snapshots,"
                            + " %.3f ms each, %.3f at most. Off it: %d searches, %.1f ms each, %d at most,"
                            + " %d tiles looked at.",
                    puppets, ticks == 0 ? 0 : nanos / 1e6 / ticks, maxNanos / 1e6, ticks,
                    bodyTicks == 0 ? 0 : nanos / 1e6 / bodyTicks,
                    snapshots, snapshots == 0 ? 0 : snapshotNanos / 1e6 / snapshots, maxSnapshotNanos / 1e6,
                    searches, searches == 0 ? 0 : (double) searchMs / searches, maxSearchMs, nodes);
        }

        synchronized void reset() {
            ticks = nanos = maxNanos = bodyTicks = 0;
            snapshots = snapshotNanos = maxSnapshotNanos = 0;
            searches = searchMs = maxSearchMs = nodes = 0;
        }
    }

    // --- commands ---------------------------------------------------------------------

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("masurium")
                .then(Commands.literal("puppet")
                        .requires(s -> s.hasPermission(2))
                        .then(Commands.literal("spawn")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .executes(Puppets::spawn)))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .executes(Puppets::remove)))
                        .then(Commands.literal("goto")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                                .executes(Puppets::goTo))))
                        .then(Commands.literal("follow")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .then(Commands.argument("player", EntityArgument.player())
                                                .executes(Puppets::follow))))
                        .then(Commands.literal("stop")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .executes(Puppets::stop)))
                        .then(Commands.literal("list").executes(Puppets::list))
                        .then(Commands.literal("stats")
                                .executes(c -> say(c.getSource(), STATS.text(ALL.size())))
                                .then(Commands.literal("reset").executes(c -> {
                                    STATS.reset();
                                    return say(c.getSource(), "puppet stats reset");
                                })))));
    }

    private static int spawn(CommandContext<CommandSourceStack> c) {
        CommandSourceStack source = c.getSource();
        String name = StringArgumentType.getString(c, "name");
        if (!NAME.matcher(name).matches()) return fail(source, name + " is no player name: 3 to 16 letters, digits or _");
        MinecraftServer server = source.getServer();
        if (server.getPlayerList().getPlayerByName(name) != null) return fail(source, name + " is already in the game");
        ServerLevel level = source.getLevel();
        GameProfile profile = new GameProfile(UUIDUtil.createOfflinePlayerUUID(name), name);
        PuppetPlayer body = new PuppetPlayer(server, level, profile);
        server.getPlayerList().placeNewPlayer(new PuppetConnection(), body, CommonListenerCookie.createInitial(profile, false));
        Vec3 at = source.getPosition();
        body.teleportTo(level, at.x, at.y, at.z, source.getRotation().y, 0);
        Puppet p = new Puppet(body);
        body.pilot = () -> pilot(p);
        ALL.put(key(name), p);
        LOG.info("[masurium] puppet {} spawned at {}", name, body.blockPosition());
        return say(source, "puppet " + name + " is in, at " + body.blockPosition().toShortString());
    }

    private static int remove(CommandContext<CommandSourceStack> c) {
        Puppet p = find(c);
        if (p == null) return 0;
        ALL.remove(key(p.name()));
        leave(p, "removed");
        return say(c.getSource(), "puppet " + p.name() + " left");
    }

    private static int goTo(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Puppet p = find(c);
        if (p == null) return 0;
        BlockPos to = BlockPosArgument.getBlockPos(c, "pos");
        p.following = null;
        p.target = to;
        p.replans = 0;
        plan(p, to, "going to " + to.toShortString());
        return say(c.getSource(), p.name() + " is searching a way to " + to.toShortString());
    }

    private static int follow(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Puppet p = find(c);
        if (p == null) return 0;
        p.following = EntityArgument.getPlayer(c, "player");
        p.target = null;
        p.plannedAt = -REPLAN_TICKS;
        p.doing = "following " + p.following.getGameProfile().getName();
        return say(c.getSource(), p.name() + " follows " + p.following.getGameProfile().getName());
    }

    private static int stop(CommandContext<CommandSourceStack> c) {
        Puppet p = find(c);
        if (p == null) return 0;
        p.following = null;
        p.target = null;
        halt(p, "standing");
        return say(c.getSource(), p.name() + " stands still");
    }

    private static int list(CommandContext<CommandSourceStack> c) {
        if (ALL.isEmpty()) return say(c.getSource(), "no puppets");
        List<String> lines = new ArrayList<>();
        for (Puppet p : ALL.values()) {
            lines.add(p.name() + " at " + p.body.blockPosition().toShortString() + ": " + p.doing);
        }
        return say(c.getSource(), String.join("\n", lines));
    }

    private static Puppet find(CommandContext<CommandSourceStack> c) {
        String name = StringArgumentType.getString(c, "name");
        Puppet p = ALL.get(key(name));
        if (p == null) fail(c.getSource(), "no puppet " + name);
        return p;
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static int say(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal("[masurium] " + text), false);
        return 1;
    }

    private static int fail(CommandSourceStack source, String text) {
        source.sendFailure(Component.literal("[masurium] " + text));
        return 0;
    }

    // --- coming and going ---------------------------------------------------------------

    /** Its death, from the body: it leaves, as a disconnected player would. */
    static void died(PuppetPlayer body) {
        ALL.values().removeIf(p -> p.body == body);
        body.pilot = null;
        body.getServer().execute(() -> body.connection.onDisconnect(
                new DisconnectionDetails(Component.literal("died"), Optional.empty(), Optional.empty())));
    }

    private static void leave(Puppet p, String why) {
        if (p.pending != null) p.pending.cancel(true);
        p.body.pilot = null;
        p.body.connection.onDisconnect(new DisconnectionDetails(Component.literal(why), Optional.empty(), Optional.empty()));
    }

    @SubscribeEvent
    public void onStopping(ServerStoppingEvent event) {
        for (Puppet p : List.copyOf(ALL.values())) leave(p, "the server stops");
        ALL.clear();
    }

    // --- what a tick costs --------------------------------------------------------------

    /** A puppet's body ticked, keys and physics: this long. */
    static void ticked(long nanos) {
        synchronized (STATS) {
            STATS.thisTick += nanos;
            STATS.bodyTicks++;
        }
    }

    @SubscribeEvent
    public void onTick(ServerTickEvent.Post event) {
        if (ALL.isEmpty()) return;
        synchronized (STATS) {
            STATS.ticks++;
            STATS.nanos += STATS.thisTick;
            STATS.maxNanos = Math.max(STATS.maxNanos, STATS.thisTick);
            STATS.thisTick = 0;
        }
    }

    // --- the keys, a tick at a time -----------------------------------------------------

    /** Before each tick of its body: what it is doing, turned into keys. */
    private static void pilot(Puppet p) {
        if (!p.body.isAlive()) return;
        follow(p, p.body.getServer().getTickCount());
        adopt(p);
        steer(p);
    }

    /** A search from where the body stands to {@code to}, on the routes thread. */
    private static void plan(Puppet p, BlockPos to, String doing) {
        if (p.pending != null) p.pending.cancel(true);
        long started = System.nanoTime();
        BlockPos from = new BlockPos(p.body.getBlockX(), floorY(p), p.body.getBlockZ());
        SnapshotWorld world = SnapshotWorld.around(p.body.serverLevel(), from, to, MARGIN, MAX_CHUNKS);
        long took = System.nanoTime() - started;
        synchronized (STATS) {
            STATS.snapshots++;
            STATS.snapshotNanos += took;
            STATS.maxSnapshotNanos = Math.max(STATS.maxSnapshotNanos, took);
        }
        Route.Point a = new Route.Point(from.getX(), from.getY(), from.getZ());
        Route.Point b = new Route.Point(to.getX(), to.getY(), to.getZ());
        // Partial routes: a stretch that gets closer is walked and searched on from its
        // end, which is what a follower needs and a goto does too (see steer).
        Route.Options options = new Route.Options(3, Route.Options.byDefault().maxNodes(), false, true)
                .withDeadline(SEARCH_MS);
        p.pending = ROUTES.submit(() -> {
            long t0 = System.currentTimeMillis();
            Route.Result r = Route.search(world, a, b, options);
            STATS.search(System.currentTimeMillis() - t0, r.looked());
            return r;
        });
        p.doing = doing;
    }

    private static void follow(Puppet p, long now) {
        ServerPlayer leader = p.following;
        if (leader == null) return;
        String doing = "following " + leader.getGameProfile().getName();
        if (leader.isRemoved() || leader.level() != p.body.level()) {
            p.following = null;
            halt(p, "lost " + leader.getGameProfile().getName());
            return;
        }
        if (now - p.plannedAt < REPLAN_TICKS || p.pending != null) return;
        p.plannedAt = now;
        if (p.body.distanceTo(leader) <= FOLLOW_GAP) {
            if (p.path != null) halt(p, doing);
            return;
        }
        p.replans = 0;
        plan(p, leader.blockPosition(), doing);
    }

    /** A finished search becomes the route to walk. */
    private static void adopt(Puppet p) {
        if (p.pending == null || !p.pending.isDone()) return;
        Route.Result r;
        try {
            r = p.pending.get();
        } catch (Exception e) {
            r = null;
        }
        p.pending = null;
        if (r != null && r.hasRoute() && r.steps().size() < 2) {       // already there
            if (p.target != null) {
                String t = p.target.toShortString();
                p.target = null;
                halt(p, "arrived at " + t);
            }
            return;
        }
        if (r == null || !r.hasRoute()) {
            p.target = null;
            halt(p, "no way: " + (r == null ? "the search failed" : r.reason()));
            return;
        }
        p.path = r.steps();
        p.next = 1;                   // the first point is where it stands
        p.partial = r.isPartial();
        p.ticks = 0;
        p.jumps = 0;
        p.lastPos = p.body.position();
    }

    /** It stops walking: keys released, a door it opened closed behind it. */
    private static void halt(Puppet p, String doing) {
        if (p.pending != null) p.pending.cancel(true);
        p.pending = null;
        p.path = null;
        release(p.body);
        closeOnStop(p);
        p.doing = doing;
    }

    private static void release(PuppetPlayer body) {
        body.zza = 0;
        body.xxa = 0;
        body.setJumping(false);
        body.setSprinting(false);
    }

    /**
     * One step along the route, as the client's Walker takes it: look at the next point,
     * push forward, jump when it is higher, when something is in the way or in water, and
     * move to the next one on standing on it.
     */
    private static void steer(Puppet p) {
        PuppetPlayer b = p.body;
        if (p.path == null) {
            release(b);
            return;
        }
        Route.Point goal = p.path.get(p.next);
        boolean lastOne = p.next == p.path.size() - 1;
        Vec3 center = new Vec3(goal.x() + 0.5, goal.y(), goal.z() + 0.5);
        double missing = horizontal(b.position(), center);

        // Arriving is STANDING on the point, not flying past its height.
        boolean settled = b.onGround() || b.isInWater();
        if (missing <= (lastOne ? NEAR_END : NEAR) && Math.abs(b.getY() - goal.y()) <= 0.5 && settled) {
            if (!lastOne) {
                p.next++;
                p.jumps = 0;
                return;
            }
            if (p.partial && p.target != null) {
                // A stretch of the way: the rest is searched from here.
                p.path = null;
                release(b);
                plan(p, p.target, "going to " + p.target.toShortString());
                return;
            }
            if (p.target != null) {
                BlockPos t = p.target;
                p.target = null;
                halt(p, String.format(Locale.ROOT, "arrived at %s (%.1f from it)", t.toShortString(), missing));
            } else {
                halt(p, p.following != null ? "following " + p.following.getGameProfile().getName() : "standing");
            }
            return;
        }

        if (++p.ticks > TICKS_MAX) {
            p.target = null;
            halt(p, "ran out of time at " + b.blockPosition().toShortString());
            return;
        }

        if (p.ticks % STUCK_TICKS == 0) {
            if (horizontal(b.position(), p.lastPos) < MIN_PROGRESS) {
                if (++p.jumps > JUMPS_MAX && !replan(p)) return;
            } else {
                p.jumps = 0;
            }
            p.lastPos = b.position();
        }

        closeIfDue(p);
        if (openIfNeeded(p, goal)) {
            release(b);
            return;
        }

        double dx = center.x - b.getX(), dz = center.z - b.getZ();
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        b.setYRot(yaw);
        b.setYHeadRot(yaw);
        b.setXRot(b.getXRot() * 0.7f);
        b.setSprinting(canRun(p, goal));
        b.zza = 1.0f;
        b.xxa = 0;
        int floor = floorY(p);
        b.setJumping(p.jumps > 0
                || goal.y() > floor
                || (b.horizontalCollision && b.onGround())
                || (b.isInWater() && goal.y() >= floor));
    }

    /** Stuck for real: another search, from here, a few times; then it gives up, saying where. */
    private static boolean replan(Puppet p) {
        if (p.following != null) {
            p.plannedAt = -REPLAN_TICKS;       // the follower's next look searches again
            p.jumps = 0;
            return true;
        }
        if (p.target == null || ++p.replans > REPLANS_MAX) {
            p.target = null;
            halt(p, "stuck at " + p.body.blockPosition().toShortString());
            return false;
        }
        plan(p, p.target, "going to " + p.target.toShortString() + " (searching again: stuck)");
        p.jumps = 0;
        return true;
    }

    /** Only on flat ground with flat ground ahead, and not on the last stretch. */
    private static boolean canRun(Puppet p, Route.Point goal) {
        if (goal.y() > floorY(p)) return false;
        if (p.next + 1 >= p.path.size()) return false;
        return p.path.get(p.next + 1).y() == goal.y();
    }

    /**
     * The tile the feet are on, which is not floor(y) on a partial block (a dirt path,
     * farmland, soul sand): the path finder counts the tile above it.
     */
    private static int floorY(Puppet p) {
        PuppetPlayer b = p.body;
        int y = (int) Math.floor(b.getY());
        BlockPos at = BlockPos.containing(b.getX(), y, b.getZ());
        var box = b.level().getBlockState(at).getCollisionShape(b.level(), at);
        if (!box.isEmpty()) {
            double cap = box.max(Direction.Axis.Y);
            if (cap > 0.5 && b.getY() >= y + cap - 0.02) return y + 1;
        }
        return y;
    }

    // --- doors: opened with the hand, closed behind it -----------------------------------

    private static boolean openIfNeeded(Puppet p, Route.Point goal) {
        PuppetPlayer b = p.body;
        BlockPos feet = new BlockPos(goal.x(), goal.y(), goal.z());
        BlockPos me = b.blockPosition();
        BlockPos which = closed(b, feet) ? feet
                : closed(b, feet.above()) ? feet.above()
                : closed(b, me) ? me
                : closed(b, me.above()) ? me.above() : null;
        if (which == null) return false;
        if (b.getEyePosition().distanceTo(Vec3.atCenterOf(which)) > PRESS_DOOR) return false;
        press(b, which);
        p.doorOpen = which;
        p.doorSide = Math.signum(projection(b, which));
        return true;
    }

    private static void closeIfDue(Puppet p) {
        if (p.doorOpen == null) return;
        PuppetPlayer b = p.body;
        double d = b.getEyePosition().distanceTo(Vec3.atCenterOf(p.doorOpen));
        if (d > 4.0 || !handheld(b, p.doorOpen) || closed(b, p.doorOpen)) {
            p.doorOpen = null;
            return;
        }
        if (d < 1.2) return;
        double s = projection(b, p.doorOpen);
        if (Math.signum(s) == p.doorSide || Math.abs(s) < DOOR_PASS) return;
        press(b, p.doorOpen);
        p.doorOpen = null;
    }

    private static void closeOnStop(Puppet p) {
        if (p.doorOpen == null) return;
        PuppetPlayer b = p.body;
        double d = b.getEyePosition().distanceTo(Vec3.atCenterOf(p.doorOpen));
        if (d >= 1.2 && d <= 4.0 && handheld(b, p.doorOpen) && !closed(b, p.doorOpen)) press(b, p.doorOpen);
        p.doorOpen = null;
    }

    private static double projection(PuppetPlayer b, BlockPos door) {
        var state = b.level().getBlockState(door);
        if (!state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) return 0;
        Direction f = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
        Vec3 c = Vec3.atCenterOf(door);
        return (b.getX() - c.x) * f.getStepX() + (b.getZ() - c.z) * f.getStepZ();
    }

    /** The hand on the door: the same use of a block a player's click makes. */
    private static void press(PuppetPlayer b, BlockPos where) {
        Vec3 center = Vec3.atCenterOf(where);
        b.lookAt(EntityAnchorArgument.Anchor.EYES, center);
        b.gameMode.useItemOn(b, b.level(), b.getMainHandItem(), InteractionHand.MAIN_HAND,
                new BlockHitResult(center, Direction.UP, where, false));
        b.swing(InteractionHand.MAIN_HAND);
    }

    private static boolean handheld(PuppetPlayer b, BlockPos where) {
        var state = b.level().getBlockState(where);
        return state.is(BlockTags.WOODEN_DOORS) || state.is(BlockTags.FENCE_GATES);
    }

    private static boolean closed(PuppetPlayer b, BlockPos where) {
        if (!handheld(b, where)) return false;
        var state = b.level().getBlockState(where);
        return state.hasProperty(BlockStateProperties.OPEN) && !state.getValue(BlockStateProperties.OPEN);
    }

    private static double horizontal(Vec3 a, Vec3 b) {
        double dx = a.x - b.x, dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
