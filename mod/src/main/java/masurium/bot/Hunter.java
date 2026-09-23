package masurium.bot;

import masurium.common.Logbook;
import masurium.common.Request;
import masurium.common.Route;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

/**
 * Hunting: chasing a specific mob and killing it. On demand.
 *
 * <p>It is the piece the {@link Guard} leaves out on purpose: the guard only ANSWERS hits
 * and does not chase; this chases. That is why they are separate classes: one is a
 * reflex, the other an errand, and mixing their rules would turn the bot into something
 * that fights when nobody asked.
 *
 * <p><b>Players only with explicit permission.</b> It was born as an absolute lock and
 * became a toggle: the {@code hunt_players} preference (false by default) is the only
 * key, and the brain only accepts changing it through the chat from the owner.
 *
 * <p>It chases like the {@link Follower} (routes replanned with restraint) and hits like
 * the Guard (best weapon, hit charged to 90%). It gives up with honor: if the mob is lost
 * for ~10 s, if the bot gets badly hurt, or if after killing the requested ones none is
 * left nearby.
 */
final class Hunter {

    /** At this distance it hits instead of walking. */
    private static final double HIT = 3.0;
    /**
     * Prey search radius. No cap of its own, like the Archer's, since good vision is the
     * point: 128 covers everything the server sends to the client, and the real limit is
     * that tracking, not a number of ours.
     */
    private static final double VIEW = 128.0;
    /** Ticks between chase replans. */
    private static final int EVERY = 10;
    /** Ticks without seeing the prey before giving up. */
    private static final int PATIENCE = 200;
    /** With less health than this it stops hunting: survival rules. */
    private static final float HP_MIN = 6.0f;
    /** Cap on kills per errand, so "hunt cows" is not a genocide. */
    static final int COUNT_MAX = 8;
    /**
     * Looking for prey: blocks per direction before giving up, length of each advance,
     * and total time cap (3 min). Before, with no prey in sight, it answered "I see none"
     * and stayed put.
     */
    private static final int SEARCH_BLOCKS = 300;
    private static final int SEARCH_SEGMENT = 48;
    private static final int SEARCH_TICKS = 20 * 180;
    /**
     * While shearing, ticks between two uses of the shears on the same sheep: the server
     * takes a moment to shear it, and one use per tick wastes shears (the same number the
     * Tamer uses for food).
     */
    private static final int BETWEEN_USES = 12;

    private final Walker walker;

    private String type;
    /** The accepted ids: `type` split by commas (cow,pig,chicken). */
    private java.util.Set<String> types = java.util.Set.of();
    private int toHunt;
    private int hunted;
    private Entity prey;
    private boolean hunting;
    private String outcome = "I am not hunting anything";
    private int ticksWithoutSeeingHer;
    private int ticksSincePlan;
    private double blueprintX, blueprintZ;
    /**
     * The missing button: kill and THEN pick up. Between one prey and the next it goes
     * through here: the item it is heading for, or null.
     */
    private ItemEntity iPickUp;
    private boolean pickingUp;
    /**
     * Dropped items that will not be picked up (trash, what does not fit, what does not
     * go in even when standing on it), by entity id. Without this, with a full backpack
     * it kept "arriving" at the same item twenty times a second, standing still and
     * marked as stuck.
     */
    private final java.util.Set<Integer> dontPickUp = new java.util.HashSet<>();
    /** Ticks on top of the item without it going in. */
    private int ticksBesideObject;
    private boolean warnedFullBackpack;
    /** Hunting without limit, ticks spent without seeing new prey. */
    private int ticksWithoutPrey;
    /** Accumulated ticks without finding a path to the current prey. */
    private int stuckTicks;
    /**
     * The tiles of the previous segment, made more expensive when replanning so it does
     * not zig-zag against a moving mob (Baritone's Favoring).
     */
    private java.util.Set<Route.Point> footsteps;
    /**
     * Prey without a path, discarded by id: the same cure as the Archer and for the same
     * bug: without it, a prey inside a house froze the hunt FOREVER and silently. Another
     * chance with every kill and at the start.
     */
    private final java.util.Set<Integer> unreachable = new java.util.HashSet<>();
    /** No prey in sight: walking in one direction until seeing one. */
    private boolean searching;
    /**
     * Whether it may go out searching when it runs out of prey, and how many times
     * already.
     */
    private boolean canSearch;
    private int searches;
    private Direction heading;
    private double originX, originZ, bestProgress;
    private int segmentsWithoutProgress, searchingTicks, rotations;
    /**
     * Shearing instead of hunting: shears instead of a sword, and only sheep with wool.
     * Everything else (searching, chasing, picking up) is the same.
     */
    private boolean shearing;
    private int ticksSinceUse;
    /**
     * Which trade the last outcome belongs to: each block of the state (hunt, shear_job)
     * only tells its own.
     */
    private boolean shearOutcome;
    /** It came through `kill` (without a bow): it reports "I killed", not "I hunted". */
    private boolean killErrand;
    /**
     * How many errands have finished: the notice key carries it so two equal errands
     * within ten minutes both notify.
     */
    private int finishedErrands;

    Hunter(Walker walker) {
        this.walker = walker;
    }

    /** @return null if the hunt started, or the reason if not */
    synchronized String begin(String type, int quantity) {
        return begin(type, quantity, false, "");
    }

    /**
     * `kill` without a bow (or against what the bow cannot handle): the same hunt with
     * the sword, reported as "I killed".
     */
    synchronized String killWithSword(String type, int quantity) {
        String failure = begin(type, quantity, false, "");
        if (failure == null) killErrand = true;
        return failure;
    }

    /**
     * The same, and with {@code search} it goes out looking for prey if it sees none: it
     * walks towards {@code toward} (north/south/east/west; empty = the way it faces) in
     * segments, looking in each one, and turns if the terrain does not let it advance. It
     * gives up with numbers: blocks walked and time.
     */
    synchronized String begin(String type, int quantity, boolean search,
                                String toward) {
        return startUp(false, type, quantity, search, toward);
    }

    /**
     * Shearing: like hunting, but with shears instead of killing. It is the same trade
     * (see, chase, act within reach, pick up what drops and go out looking if there is
     * none) with two differences: only adult sheep WITH wool ({@link
     * Sheep#readyForShearing}), and within reach it uses the shears instead of the sword.
     * The sheep stays alive and bare; the wool drops to the ground and is picked up like
     * everything else. Without shears it does not go out.
     */
    synchronized String shear(int quantity, boolean search, String toward) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return "I am in no world";
        if (shearsSlot(p, false) < 0) {
            return "I have no shears: they are crafted with 2 iron "
                   + "ingots (`craft_item shears`)";
        }
        return startUp(true, "sheep", quantity, search, toward);
    }

    private String startUp(boolean shear, String type, int quantity,
                            boolean search, String toward) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return "I am in no world";
        if (!p.isAlive()) return "I am dead; I must respawn first";

        this.shearing = shear;
        this.killErrand = false;
        this.ticksSinceUse = BETWEEN_USES;
        this.type = type;
        this.types = new java.util.HashSet<>();
        for (String t : type.split(",")) if (!t.strip().isEmpty()) this.types.add(t.strip());
        // count <= 0 = NO LIMIT: it hunts until told "stop" or until prey stops showing
        // up for a while.
        this.toHunt = quantity <= 0 ? Integer.MAX_VALUE
                : Math.min(COUNT_MAX, quantity);
        this.hunted = 0;
        this.pickingUp = false;
        this.iPickUp = null;
        this.dontPickUp.clear();
        this.ticksBesideObject = 0;
        this.warnedFullBackpack = false;
        this.ticksWithoutPrey = 0;
        this.stuckTicks = 0;
        this.footsteps = null;
        this.unreachable.clear();
        this.searching = false;
        // It ALWAYS searches when the reachable prey runs out, with or without a limit:
        // if it finds no animals, it explores and searches (otherwise it hunted a couple
        // and stood still). The `search` parameter only decides whether it goes out
        // searching when there is NONE in sight at the start (the server negotiates that
        // first).
        this.canSearch = true;
        this.searches = 0;
        this.prey = searchPrey(mc, p);
        if (prey == null) {
            if (!search) {
                return shearing
                        ? String.format("I see no sheep with wool within %d "
                                + "blocks", (int) VIEW)
                        : String.format("I see no %s within %d blocks",
                                type, (int) VIEW);
            }
            startSearch(p, toward);
        }
        this.hunting = true;
        this.outcome = null;
        this.ticksWithoutSeeingHer = 0;
        this.ticksSincePlan = EVERY;
        this.blueprintX = Double.NaN;
        String verb = shearing ? "shear" : "hunt";
        String what = shearing ? "sheep with wool" : type;
        Logbook.note(category(), toHunt == Integer.MAX_VALUE
                ? "going out to " + verb + " " + what + " no limit"
                : String.format("going out to %s %d %s", verb, toHunt, what));
        return null;
    }

    /** The logbook category: "hunting" or "shearing". */
    private String category() {
        return shearing ? "shearing" : "hunting";
    }

    /** Done: dead when hunting, sheared when shearing. */
    private boolean list(Entity e) {
        return shearing ? e instanceof Sheep s && s.isSheared() : !e.isAlive();
    }

    /**
     * Finishes ON ITS OWN: like {@link #stop}, but notifying the brain, the {@link
     * Archer}'s pattern. Without a notice, "kill that villager" ended in the logbook and
     * the state and nobody woke the brain: no "target eliminated" ever arrived. Only for
     * its own endings (errand done, no prey, lost, low health, search exhausted, broken
     * shears); the "I was asked to X" ones come from the brain and need not be told back
     * to it.
     */
    private void finish(String how) {
        stop(how);
        String what = shearing ? "the shearing" : killErrand ? "the kill errand" : "the hunt";
        Needs.warn("hunt_done:" + (++finishedErrands),
                "I finished " + what + ": " + how);
    }

    /** "I killed" if it came through `kill`, "I hunted" if through `hunt`. */
    private String letHunt() {
        return killErrand ? "I killed" : "I hunted";
    }

    synchronized void stop(String because) {
        if (hunting) {
            Logbook.note(category(), "I drop " + category() + ": " + because);
        }
        hunting = false;
        prey = null;
        outcome = because;
        shearOutcome = shearing;
    }

    synchronized void tick() {
        if (!hunting) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) { stop("I left the world"); return; }
        if (!p.isAlive()) { stop("I was killed while hunting"); return; }
        if (p.getHealth() < HP_MIN) {
            finish(String.format("I drop " + category() + ": I have very little "
                    + "hp left (%.1f); I have %d of %d", p.getHealth(), hunted, toHunt));
            return;
        }

        if (searching) {
            searchPreys(mc, p);
            return;
        }

        // While shearing, a sheep that dies (or gets killed) does not count: another one.
        if (shearing && prey != null && !prey.isAlive()) prey = null;
        // Is the current prey still standing? (shearing: does it still have wool?)
        if (prey == null || list(prey)) {
            if (prey != null) {
                hunted++;
                Logbook.note(category(), shearing
                        ? (toHunt == Integer.MAX_VALUE
                            ? String.format("I sheared a sheep (%d)", hunted)
                            : String.format("I sheared a sheep (%d of %d)",
                                    hunted, toHunt))
                        : toHunt == Integer.MAX_VALUE
                        ? String.format("I took down %s (%d)", type, hunted)
                        : String.format("I took down %s (%d of %d)",
                                type, hunted, toHunt));
                prey = null;
                pickingUp = true;   // first what it dropped, then the next one
                unreachable.clear();   // another chance for the ones in the house
            }

            // The other half of the trade: pick up what dropped before the next prey. The
            // item has to be touched to go into the inventory, so it walks over it.
            if (pickingUp && pickUpDropped(mc, p)) return;

            if (hunted >= toHunt) {
                walker.stop(category() + " finished");
                finish(shearing
                        ? String.format("I sheared %d sheep and picked up the wool", hunted)
                        : String.format("%s %d %s%s and picked up what they dropped",
                                letHunt(), hunted, type,
                                killErrand ? " by sword" : ""));
                return;
            }
            prey = searchPrey(mc, p);
            if (prey == null && canSearch && searches < 2) {
                startSearch(p, "");
                return;
            }
            if (prey == null) {
                // Without a limit it waits a while in case more appear; with a fixed
                // number it finishes and says how it went.
                if (toHunt == Integer.MAX_VALUE) {
                    if (++ticksWithoutPrey < 600) return;
                    walker.stop("no prey");
                    finish(shearing
                            ? String.format("I sheared %d sheep; half a minute "
                                    + "without seeing more with wool", hunted)
                            : String.format("%s %d %s; half a minute without "
                                    + "seeing more", letHunt(), hunted, type));
                    return;
                }
                walker.stop("no prey");
                finish(shearing
                        ? String.format("I sheared %d of %d sheep; I see no more with "
                                + "wool nearby", hunted, toHunt)
                        : String.format("%s %d of %d %s; I see no more nearby",
                                letHunt(), hunted, toHunt, type));
                return;
            }
            ticksWithoutPrey = 0;
            stuckTicks = 0;
            blueprintX = Double.NaN;
        }

        if (prey.isRemoved()) {
            if (++ticksWithoutSeeingHer > PATIENCE) {
                finish(String.format("I lost sight of the %s; I have %d of %d",
                        type, hunted, toHunt));
            }
            return;
        }
        ticksWithoutSeeingHer = 0;

        double d = p.distanceTo(prey);
        // Hunting, at hitting distance; shearing, within arm's reach (the same reach the
        // Tamer feeds with).
        if (shearing ? p.canInteractWithEntity(prey, 0.0) : d <= HIT) {
            if (walker.walking()) walker.stop("prey within reach");
            stuckTicks = 0;
            if (shearing) shear(mc, p, prey);
            else hit(mc, p, prey);
            return;
        }

        // The chase, with the Follower's restraint: mobs move a lot and replanning every
        // tick would burn the game thread.
        if (++ticksSincePlan < EVERY) return;
        boolean moved = Double.isNaN(blueprintX)
                || Math.abs(prey.getX() - blueprintX)
                   + Math.abs(prey.getZ() - blueprintZ) > 1.5;
        if (walker.walking() && !moved) return;
        ticksSincePlan = 0;

        ClientWorld world = new ClientWorld(mc.level);
        Route.Point here = MasuriumBot.whereAmI(world, p);
        Route.Point aimTarget = new Route.Point((int) Math.floor(prey.getX()),
                (int) Math.floor(prey.getY()), (int) Math.floor(prey.getZ()));
        // The goal is "within 2 of the mob": any tile of the ring will do and the path
        // finder picks it (Baritone's second idea). With PARTIAL routes (for distant
        // prey, the segment that gets closer is walked and the replan every EVERY ticks
        // continues from its end), a 30 ms deadline (the search runs on the game thread)
        // and the previous segment's tiles made more expensive so it does not zig-zag.
        Route.Result r = Route.search(world, here,
                Route.Meta.near(aimTarget, 2.0),
                new Route.Options(MasuriumBot.safeFall(p.getHealth()), 8_000,
                        true, true, 30, footsteps)
                        .breaking(Preferences.is("break_to_advance")));
        // A single step = "I am already there": on the goal ring but unable to reach the
        // prey (a floor in between, typically) is not progress. It counts as stuck, or it
        // keeps "arriving" forever (the Archer's loop of 440 arrivals applies here too).
        if (r.hasRoute() && r.steps().size() > 1
                && walker.follow(r.steps(), x -> null) == null) {
            footsteps = new java.util.HashSet<>(r.steps());
            blueprintX = prey.getX();
            blueprintZ = prey.getZ();
            stuckTicks = 0;
            return;
        }
        // Far away, the direct route does not work (8k nodes): the FillWorker's protocol,
        // X and Z first and the Y up close, which hunting also needs with a view of 128.
        if (d > 30 && MasuriumBot.approachTo(p, world, here,
                aimTarget.x(), aimTarget.z(), walker)) {
            blueprintX = prey.getX();
            blueprintZ = prey.getZ();
            stuckTicks = 0;
            return;
        }
        // Prey with no walkable spot or no path (typically: inside a house). Discard it
        // with a note after ~5 s instead of retrying the route silently forever; the bug
        // seen in the Archer applies here identically.
        stuckTicks += EVERY;
        if (stuckTicks >= 100) {
            Logbook.note(category(), String.format(
                    "I find no path to the %s; I discard it", type));
            unreachable.add(prey.getId());
            prey = null;
            stuckTicks = 0;
        }
    }

    /**
     * Starts a search from where it is, towards {@code toward} or where it faces. Prey
     * discarded for having no path count again: from another place they may be reachable.
     */
    private void startSearch(LocalPlayer p, String toward) {
        Direction d = switch (toward == null ? "" : toward.trim().toLowerCase()) {
            case "north" -> Direction.NORTH;
            case "south" -> Direction.SOUTH;
            case "east" -> Direction.EAST;
            case "west" -> Direction.WEST;
            default -> p.getDirection();
        };
        this.searching = true;
        this.searches++;
        this.unreachable.clear();
        this.heading = d;
        this.originX = p.getX();
        this.originZ = p.getZ();
        this.bestProgress = 0;
        this.segmentsWithoutProgress = 0;
        this.searchingTicks = 0;
        this.ticksWithoutPrey = 0;
        this.rotations = 0;
        Logbook.note(category(), String.format(
                "I see no %s I can reach: going out looking for them to the %s",
                type, name(d)));
    }

    /**
     * Walks along the heading in segments until seeing a prey. Each segment is {@value
     * #SEARCH_SEGMENT} blocks in the direction (X and Z first, any height); if three
     * segments in a row make no progress (sea, cliff, no route) it turns right, up to
     * four times.
     */
    private void searchPreys(Minecraft mc, LocalPlayer p) {
        prey = searchPrey(mc, p);
        if (prey != null) {
            searching = false;
            if (walker.walking()) walker.stop("prey in sight");
            Logbook.note(category(), String.format(
                    "I saw a %s %d blocks away; after %d blocks of searching",
                    type, (int) p.distanceTo(prey), (int) walked(p)));
            ticksSincePlan = EVERY;
            blueprintX = Double.NaN;
            stuckTicks = 0;
            return;
        }
        if (++searchingTicks > SEARCH_TICKS) {
            walker.stop("search finished");
            finish(String.format("I hunted %d %s; I looked for more for 3 minutes (%d "
                    + "blocks, to the %s) and saw none", hunted, type,
                    (int) walked(p), name(heading)));
            return;
        }
        if (++ticksSincePlan < EVERY) return;
        ticksSincePlan = 0;
        if (walker.walking()) return;

        double progress = walked(p);
        if (progress >= SEARCH_BLOCKS) {
            walker.stop("search finished");
            finish(String.format("I hunted %d %s; I looked for more %d blocks to the %s "
                    + "and saw none", hunted, type, (int) progress, name(heading)));
            return;
        }
        if (progress < bestProgress + 2.0) {
            if (++segmentsWithoutProgress >= 3) {
                if (++rotations > 4) {
                    walker.stop("search finished");
                    finish(String.format("I looked for %s on all four sides and "
                            + "I find neither a path nor prey", type));
                    return;
                }
                Direction latest = heading.getClockWise();
                Logbook.note(category(), String.format(
                        "I make no progress to the %s; trying to the %s",
                        name(heading), name(latest)));
                heading = latest;
                originX = p.getX();
                originZ = p.getZ();
                bestProgress = 0;
                segmentsWithoutProgress = 0;
            }
        } else {
            bestProgress = progress;
            segmentsWithoutProgress = 0;
        }
        ClientWorld world = new ClientWorld(mc.level);
        Route.Point here = MasuriumBot.whereAmI(world, p);
        int ox = here.x() + heading.getStepX() * SEARCH_SEGMENT;
        int oz = here.z() + heading.getStepZ() * SEARCH_SEGMENT;
        if (!MasuriumBot.approachTo(p, world, here, ox, oz, walker)) {
            segmentsWithoutProgress++;
        }
    }

    /** Blocks advanced along the heading since it started (or turned). */
    private double walked(LocalPlayer p) {
        return Math.max(0, (p.getX() - originX) * heading.getStepX()
                + (p.getZ() - originZ) * heading.getStepZ());
    }

    private static String name(Direction d) {
        return switch (d) {
            case NORTH -> "north";
            case SOUTH -> "south";
            case EAST -> "east";
            case WEST -> "west";
            default -> d.getName();
        };
    }

    /** The Guard's hit: best weapon, charged to 90%, looking at the mob. */
    private void hit(Minecraft mc, LocalPlayer p, Entity who) {
        int weapon = WeaponPicker.prepareWeapon(mc, p);
        if (p.getInventory().selected != weapon) {
            p.getInventory().selected = weapon;
            return;   // the tick lost on purpose: switching resets the charge
        }
        if (p.getAttackStrengthScale(0.0f) < 0.9f) return;
        p.lookAt(EntityAnchorArgument.Anchor.EYES,
                who.position().add(0, who.getBbHeight() / 2, 0));
        mc.gameMode.attack(p, who);
        p.swing(InteractionHand.MAIN_HAND);
    }

    /**
     * The shears on the sheep, looking at it: the Tamer's gesture with food. With a pause
     * between uses and the lost tick when switching slots, since the server has to see
     * the new hand before the use.
     */
    private void shear(Minecraft mc, LocalPlayer p, Entity e) {
        if (++ticksSinceUse < BETWEEN_USES) return;
        int r = shearsSlot(p, true);
        if (r < 0) {
            walker.stop("no shears");
            finish(String.format("my shears broke; I sheared %d sheep",
                    hunted));
            return;
        }
        if (p.getInventory().selected != r) {
            p.getInventory().selected = r;
            return;   // the tick lost on purpose
        }
        ticksSinceUse = 0;
        p.lookAt(EntityAnchorArgument.Anchor.EYES,
                e.position().add(0, e.getBbHeight() / 2, 0));
        InteractionResult res = mc.gameMode.interact(p, e, InteractionHand.MAIN_HAND);
        if (res.consumesAction()) p.swing(InteractionHand.MAIN_HAND);
    }

    /**
     * The slot with shears, or -1. With {@code climb}, if they are only in the backpack
     * they are brought up to the hotbar (like the Tamer with food); without it, knowing
     * it carries them is enough.
     */
    private static int shearsSlot(LocalPlayer p, boolean climb) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty() || !BuiltInRegistries.ITEM.getKey(stack.getItem())
                    .getPath().equals("shears")) continue;
            if (i < 9 || !climb) return i;
            return MasuriumBot.takeFromBackpack(p, "shears");
        }
        return -1;
    }

    /**
     * Walks to what dropped and steps on it. @return true if it is still at it.
     *
     * <p>Items within 12 blocks, nearest first. Picking up is TOUCHING: the game puts it
     * in the inventory on contact, so arriving with the usual collector's fine arrival
     * (0.4) is enough.
     */
    private boolean pickUpDropped(Minecraft mc, LocalPlayer p) {
        if (iPickUp != null && (iPickUp.isRemoved() || !iPickUp.isAlive())) {
            iPickUp = null;                       // picked up (or despawned)
            ticksBesideObject = 0;
        }
        // On top of the item and it does not go in (full backpack, or the game does not
        // give it): two seconds and it is left. Before, a pointless one-step replan ran
        // every tick, forever.
        if (iPickUp != null && p.distanceTo(iPickUp) < 1.5) {
            if (++ticksBesideObject > 40) {
                Logbook.note(category(), "I cannot pick up "
                        + nameOf(iPickUp.getItem()) + "; I leave it on the ground");
                dontPickUp.add(iPickUp.getId());
                iPickUp = null;
                ticksBesideObject = 0;
            }
        } else {
            ticksBesideObject = 0;
        }
        if (iPickUp == null) {
            double best = Double.MAX_VALUE;
            for (Entity e : mc.level.getEntities(p,
                    new AABB(p.blockPosition()).inflate(12),
                    x -> x instanceof ItemEntity && x.isAlive())) {
                ItemEntity it = (ItemEntity) e;
                if (dontPickUp.contains(it.getId())) continue;
                // The trash the bot itself tosses to make room is not picked up again:
                // tossing dirt and picking it up was the loop.
                if (Trash.isTrash(nameOf(it.getItem()))) continue;
                // Nor what does not fit: it notifies once and carries on.
                var inv = p.getInventory();
                if (inv.getFreeSlot() < 0
                        && inv.getSlotWithRemainingSpace(it.getItem()) < 0) {
                    if (!warnedFullBackpack) {
                        warnedFullBackpack = true;
                        Logbook.note(category(), "backpack full: what drops "
                                + "stays on the ground");
                    }
                    continue;
                }
                double d = p.distanceTo(e);
                if (d < best) { best = d; iPickUp = it; }
            }
            if (iPickUp == null) {
                pickingUp = false;              // nothing left (that fits)
                return false;
            }
        }
        if (walker.walking()) return true;  // on the way
        ClientWorld world = new ClientWorld(mc.level);
        Route.Point here = MasuriumBot.whereAmI(world, p);
        Route.Point goal = whereStanding(world, iPickUp);
        if (goal == null) {
            iPickUp = null;                       // floating oddly: next one
            return true;
        }
        Route.Result r = Route.search(world, here, goal, new Route.Options(
                MasuriumBot.safeFall(p.getHealth()), 8_000, false));
        if (!r.hasRoute()
                || walker.follow(r.steps(), x -> null, 0.4) != null) {
            iPickUp = null;                       // unreachable: next one
        }
        return true;
    }

    /**
     * The nearest prey of the requested type. Players only enter the filter if the {@code
     * hunt_players} preference is true; with false (the default) there is no path that
     * reaches them.
     */
    private Entity searchPrey(Minecraft mc, LocalPlayer p) {
        boolean pvp = Preferences.is("hunt_players");
        Entity best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity e : mc.level.getEntities(p,
                new AABB(p.blockPosition()).inflate(VIEW),
                x -> x instanceof LivingEntity && x.isAlive()
                     && (pvp || !(x instanceof Player)))) {
            if (!isTheType(e) || unreachable.contains(e.getId())) continue;
            // Shearing: only adult sheep WITH wool; a sheared one is not prey.
            if (shearing && !(e instanceof Sheep s && s.readyForShearing())) continue;
            double dist = p.distanceTo(e);
            if (dist < bestDist) {
                bestDist = dist;
                best = e;
            }
        }
        return best;
    }

    /**
     * The type id (cow, zombie, player...) or, for a player, also their NAME: "kill
     * Player1" arrives with the name, not with "player", and comparing only ids left it
     * with a false "I see no player1". The player lock already filtered before.
     */
    private boolean isTheType(Entity e) {
        // Several at once (cow,pig,chicken): the nearest of any of them.
        if (types.contains(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath())) {
            return true;
        }
        // And the mob's NAME TAG: "kill the villager Bob" arrives as "bob", and without
        // this the name meant nothing and the nearest villager fell, which was a baby.
        if (e.hasCustomName() && types.contains(
                e.getCustomName().getString().strip().toLowerCase())) {
            return true;
        }
        return e instanceof Player pj
                && types.contains(pj.getGameProfile().getName().toLowerCase());
    }

    private static String nameOf(net.minecraft.world.item.ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
    }

    private static Route.Point whereStanding(ClientWorld m, Entity him) {
        int x = (int) Math.floor(him.getX());
        int z = (int) Math.floor(him.getZ());
        int y0 = (int) Math.floor(him.getY());
        for (int dy = 1; dy >= -4; dy--) {
            if (m.canStand(x, y0 + dy, z)) {
                return new Route.Point(x, y0 + dy, z);
            }
        }
        return null;
    }

    /**
     * If whoever it is chasing now is a PLAYER, their name; otherwise null. The guard
     * uses it so it does not complain about being hit when the bot started the fight.
     */
    synchronized String playerInCrosshair() {
        return prey instanceof net.minecraft.world.entity.player.Player pj
                ? pj.getGameProfile().getName() : null;
    }

    /**
     * The `hunt` block of the state: only sword hunting. While shearing, this says "I am
     * not hunting anything" and its own goes in {@link #shearState}.
     */
    synchronized String state() {
        if (!hunting || shearing) {
            return String.format("{\"hunting\":false,\"outcome\":\"%s\"}",
                    Request.escape(shearOutcome || outcome == null
                            ? "I am not hunting anything" : outcome));
        }
        return String.format(
                "{\"hunting\":true,\"type\":\"%s\",\"hunted\":%d,\"of\":%d%s}",
                Request.escape(type), hunted, toHunt,
                searching ? ",\"searching\":\"to the " + name(heading) + "\"" : "");
    }

    /** The `shear_job` block of the state: shearing with shears. */
    synchronized String shearState() {
        if (!hunting || !shearing) {
            return String.format("{\"shearing\":false,\"outcome\":\"%s\"}",
                    Request.escape(shearOutcome && outcome != null
                            ? outcome : "I have not sheared anything"));
        }
        return String.format(
                "{\"shearing\":true,\"sheared\":%d,\"of\":%d%s}",
                hunted, toHunt,
                searching ? ",\"searching\":\"to the " + name(heading) + "\"" : "");
    }

    synchronized boolean hunting() {
        return hunting;
    }
}
