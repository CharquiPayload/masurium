package marionette.bot;

import marionette.common.Logbook;
import marionette.common.Request;
import marionette.common.Route;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

/**
 * Mounting, riding a horse and getting off.
 *
 * <p>The scope is exactly that: mount the horse, move around, and dismount. And how the
 * horse is chosen: a name tag's name travels to every client, so "mount Thunder" looks
 * for exactly that one; without a name, the nearest tamed one.
 *
 * <p>What it does inside, in order: get close (with the {@link Walker}'s feet), put a
 * CLEAN hand forward (with food in hand the horse gets fed, with a saddle it gets
 * saddled, with a lead it gets tied, and with anything in hand an untamed horse gets
 * angry instead of letting itself be mounted), and use. If it is not tamed, mounting it
 * IS taming it: it throws you off a few times and then gives in, so it insists up to
 * {@value #ATTEMPTS_MAX} times. Tamed but without a saddle it cannot be steered: if the
 * bot carries a saddle it puts it on first; if not, it says so and does not mount,
 * because a bot sitting on a horse that does not obey is a stuck bot by another name.
 *
 * <p>Riding does not live here: once mounted, the same keys that move the bot move the
 * horse, so the Walker still rules. What changes (that "I am on the ground" now means
 * "the horse is on the ground", that the height is the horse's, and that jumping works in
 * bursts) is in the Walker. What it does NOT know yet: that the horse is wider than the
 * bot and does not fit through one-block gaps nor build towers; if it gets stuck, get off
 * ({@link #dismount}) and carry on on foot.
 *
 * <p>Getting off is the faked sneak key, like every key in this mod: the server dismounts
 * on seeing the packet, and {@code stopRiding} on the client would only desync it.
 */
final class Rider {

    /** How far it looks for a horse. */
    private static final double VIEW = 32.0;
    /**
     * The hand's reach on a mob, measured as the SERVER measures it: from the eyes to the
     * nearest point of the horse's box, three blocks. It used to be measured center to
     * center and 2.5 fell short: the horse is 1.4 wide and the bot reached the end of the
     * route 2.8 from its center, "arrived" and asked for a route again, thirty-four times
     * in eighteen seconds, without ever touching it.
     */
    private static final double REACH = 2.9;
    /**
     * Replans in a row without getting closer before giving up: the horse may be behind a
     * fence, and no route helps there.
     */
    private static final int NO_APPROACH_MAX = 5;
    /** Times it tries to mount before giving up: taming throws you off several times. */
    private static final int ATTEMPTS_MAX = 8;
    /** Ticks between one attempt and the next: let the server answer. */
    private static final int WAIT = 25;
    /** Ticks between replans of the approach. */
    private static final int PLAN = 10;
    /** Ticks holding the sneak key before calling it impossible. */
    private static final int DISMOUNT_MAX = 60;

    private enum Phase { NONE, APPROACHING, MOUNTING, DISMOUNTING }

    private final Walker walker;
    private Phase phase = Phase.NONE;
    private AbstractHorse horse;
    private int attempts, wait, planTicks, dismountTicks;
    /**
     * The closest it has been to the horse in this approach, and the routes in a row
     * without improving that.
     */
    private double bestDistance;
    private int withoutApproaching;
    private String outcome = "I have not mounted anything";

    Rider(Walker walker) {
        this.walker = walker;
    }

    /** @return null if it goes for it, or the reason in words */
    synchronized String mount(String name) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return "I am in no world";
        if (!p.isAlive()) return "I am dead; I must respawn first";
        if (p.isPassenger()) {
            return "I am already mounted on " + nameOf(p.getVehicle());
        }
        String requested = name == null ? "" : name.trim();
        AbstractHorse chosenOne = null;
        double best = Double.MAX_VALUE;
        AbstractHorse untamed = null;
        double bestUntamed = Double.MAX_VALUE;
        for (Entity e : mc.level.getEntities(p,
                new AABB(p.blockPosition()).inflate(VIEW),
                x -> x.isAlive() && x instanceof AbstractHorse)) {
            AbstractHorse h = (AbstractHorse) e;
            double d = p.distanceTo(h);
            if (!requested.isEmpty()) {
                if (!h.hasCustomName()
                        || !h.getCustomName().getString().equalsIgnoreCase(requested)) {
                    continue;
                }
                if (d < best) { best = d; chosenOne = h; }
            } else if (h.isTamed()) {
                if (d < best) { best = d; chosenOne = h; }
            } else if (d < bestUntamed) {
                bestUntamed = d;
                untamed = h;
            }
        }
        if (chosenOne == null && requested.isEmpty()) chosenOne = untamed;
        if (chosenOne == null) {
            return requested.isEmpty()
                    ? String.format("I see no horse within %d blocks", (int) VIEW)
                    : String.format("I see no horse called '%s' within %d "
                            + "blocks; if it has a name tag, the name has to "
                            + "be exact", requested, (int) VIEW);
        }
        if (chosenOne.isBaby()) {
            return nameOf(chosenOne) + " is a foal: it cannot be mounted";
        }
        if (chosenOne.isTamed() && !chosenOne.isSaddled() && slotOf(p, Items.SADDLE) < 0) {
            return nameOf(chosenOne) + " is tamed but has no saddle, and I "
                    + "carry none: without a saddle I can get on but not "
                    + "steer it. Put one on it, or give me a saddle";
        }
        this.horse = chosenOne;
        this.attempts = 0;
        this.wait = 0;
        this.planTicks = PLAN;
        this.bestDistance = Double.MAX_VALUE;
        this.withoutApproaching = 0;
        this.phase = Phase.APPROACHING;
        this.outcome = null;
        Logbook.note("mount", String.format("going to mount %s (%d blocks away%s)",
                nameOf(chosenOne), (int) bestDist(p, chosenOne),
                chosenOne.isTamed() ? "" : ", untamed: I will have to insist"));
        return null;
    }

    /** @return null if it is going to get off, or the reason */
    synchronized String dismount() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return "I am in no world";
        if (!p.isPassenger()) return "I am not mounted on anything";
        // The Walker's feet restore the original keyboard on stopping, which is the one
        // that reads the faked sneak key.
        if (walker.walking()) walker.stop("getting off the horse");
        this.phase = Phase.DISMOUNTING;
        this.dismountTicks = 0;
        this.outcome = null;
        mc.options.keyShift.setDown(true);
        Logbook.note("mount", "getting off " + nameOf(p.getVehicle()));
        return null;
    }

    /**
     * Drops whatever it was doing (approaching or insisting). It does NOT get off the
     * horse: that is a separate order, and getting off in the middle of a trip because of
     * a /stop would be worse than staying seated.
     */
    synchronized void stop(String because) {
        if (phase == Phase.NONE) return;
        if (phase == Phase.DISMOUNTING) {
            Minecraft.getInstance().options.keyShift.setDown(false);
        }
        Logbook.note("mount", "I leave it: " + because);
        phase = Phase.NONE;
        horse = null;
        outcome = because;
    }

    synchronized void tick() {
        if (phase == Phase.NONE) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) { stop("I left the world"); return; }
        if (!p.isAlive()) { stop("I was killed"); return; }

        if (phase == Phase.DISMOUNTING) {
            if (!p.isPassenger()) {
                mc.options.keyShift.setDown(false);
                finish("standing again");
                return;
            }
            if (++dismountTicks > DISMOUNT_MAX) {
                mc.options.keyShift.setDown(false);
                finish("I could not get off: the server does not release me");
            }
            return;
        }

        if (horse == null || !horse.isAlive() || horse.isRemoved()) {
            finish("the horse is gone");
            return;
        }
        if (p.isPassenger()) {
            if (p.getVehicle() != horse) {
                finish("I ended up mounted on something else: " + nameOf(p.getVehicle()));
                return;
            }
            if (!horse.isSaddled()) {
                // Just tamed and without a saddle: I get off, since seated I cannot steer
                // it.
                Logbook.note("mount", "tamed, but without a saddle I cannot steer it");
                phase = Phase.DISMOUNTING;
                dismountTicks = 0;
                mc.options.keyShift.setDown(true);
                outcome = null;
                return;
            }
            finish(String.format("mounted on %s%s", nameOf(horse),
                    attempts > 1 ? String.format(" (it gave in at attempt %d)", attempts) : ""));
            return;
        }

        double d = p.distanceTo(horse);
        if (phase == Phase.APPROACHING) {
            if (inReach(p)) {
                if (walker.walking()) walker.stop("I am already next to the horse");
                phase = Phase.MOUNTING;
                wait = 0;
                return;
            }
            if (walker.walking()) return;
            if (++planTicks < PLAN) return;
            planTicks = 0;
            // Did this route get me any closer? If not, it counts; five in a row without
            // gaining half a block and the truth is told: it cannot be reached.
            if (d < bestDistance - 0.5) {
                bestDistance = d;
                withoutApproaching = 0;
            } else if (++withoutApproaching >= NO_APPROACH_MAX) {
                finish(String.format("I cannot reach %s: I stay %.1f "
                        + "blocks away and no route gets me closer. Is it in a "
                        + "pen or behind a fence? Open a way for me or "
                        + "get it out", nameOf(horse), d));
                return;
            }
            ClientWorld world = new ClientWorld(mc.level);
            Route.Point here = MarionetteBot.whereAmI(world, p);
            Route.Point where = new Route.Point((int) Math.floor(horse.getX()),
                    (int) Math.floor(horse.getY()), (int) Math.floor(horse.getZ()));
            Route.Result r = Route.search(world, here, Route.Meta.near(where, 2.0),
                    new Route.Options(MarionetteBot.safeFall(p.getHealth()), 6_000,
                            true, true).withDeadline(30));
            if (r.hasRoute() && r.steps().size() > 1) {
                walker.follow(r.steps(), x -> null);
            } else {
                finish(String.format("I find no path to %s (%d blocks away)",
                        nameOf(horse), (int) d));
            }
            return;
        }

        // MOUNTING: look at it, clean hand, use; and wait for the server to answer before
        // insisting.
        if (!inReach(p) && d > REACH + 1.5) {
            // It left (or threw me far): approach again.
            phase = Phase.APPROACHING;
            planTicks = PLAN;
            return;
        }
        if (wait > 0) { wait--; return; }
        if (attempts >= ATTEMPTS_MAX) {
            finish(String.format("%s will not let me mount: %d attempts",
                    nameOf(horse), attempts));
            return;
        }
        p.lookAt(EntityAnchorArgument.Anchor.EYES, horse.position());
        if (horse.isTamed() && !horse.isSaddled()) {
            int saddle = slotOf(p, Items.SADDLE);
            if (saddle >= 0) {
                p.getInventory().selected = saddle;
                mc.gameMode.interact(p, horse, InteractionHand.MAIN_HAND);
                Logbook.note("mount", "putting the saddle on " + nameOf(horse));
                wait = WAIT;
                return;
            }
        }
        int clean = cleanHand(p);
        if (clean < 0) {
            finish("I have no free slot nor one with a plain block "
                    + "to mount with a clean hand");
            return;
        }
        p.getInventory().selected = clean;
        mc.gameMode.interact(p, horse, InteractionHand.MAIN_HAND);
        attempts++;
        wait = WAIT;
        Logbook.note("mount", String.format("attempt %d to mount %s",
                attempts, nameOf(horse)));
    }

    /**
     * Can my hand reach it? From the eyes to the horse's box, as the server checks it
     * when receiving the use.
     */
    private boolean inReach(LocalPlayer p) {
        return horse != null && horse.getBoundingBox()
                .distanceToSqr(p.getEyePosition()) <= REACH * REACH;
    }

    /**
     * Finish with a notice: without a notice there is no turn, and a ride that ends
     * silently leaves the bot sitting there without anyone knowing it is ready.
     */
    private void finish(String how) {
        phase = Phase.NONE;
        horse = null;
        outcome = how;
        Logbook.note("mount", how);
        Needs.warn("mount:" + how, "about the horse: " + how);
    }

    /**
     * A hotbar slot that does nothing odd when used on a horse: empty if there is one;
     * otherwise a plain block. Never food, saddle, lead, name tag nor armor: each of
     * those does ITS thing.
     */
    private static int cleanHand(LocalPlayer p) {
        var inv = p.getInventory();
        for (int i = 0; i < 9; i++) if (inv.getItem(i).isEmpty()) return i;
        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.getItem(i);
            if (s.getItem() instanceof BlockItem && !s.is(Items.CARVED_PUMPKIN)) return i;
        }
        return -1;
    }

    /**
     * The hotbar slot with that item, bringing it up from the backpack if needed; -1 if
     * the bot does not carry it.
     */
    private static int slotOf(LocalPlayer p, net.minecraft.world.item.Item what) {
        var inv = p.getInventory();
        for (int i = 0; i < 9; i++) if (inv.getItem(i).is(what)) return i;
        return MarionetteBot.takeFromBackpack(p,
                BuiltInRegistries.ITEM.getKey(what).getPath());
    }

    private static double bestDist(LocalPlayer p, Entity e) {
        return p.distanceTo(e);
    }

    /** What it is called: its name tag if it has one, otherwise the type ("horse"). */
    static String nameOf(Entity e) {
        if (e == null) return "none";
        if (e.hasCustomName()) return e.getCustomName().getString();
        return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
    }

    synchronized String state() {
        LocalPlayer p = Minecraft.getInstance().player;
        boolean mounted = p != null && p.isPassenger();
        String at = mounted ? "\"" + Request.escape(nameOf(p.getVehicle())) + "\"" : "null";
        // The uuid, not the position: the position expires as soon as the horse takes two
        // steps, the uuid never changes. With it, the server mod can say where the horse
        // is even a thousand blocks away and unloaded (a horse left alone wanders off).
        String who = mounted
                ? "\"" + p.getVehicle().getUUID() + "\"" : "null";
        return String.format("{\"mounted\":%b,\"at\":%s,\"uuid\":%s,"
                + "\"doing\":\"%s\",\"outcome\":%s}", mounted, at, who,
                phase == Phase.NONE ? "none" : phase.name().toLowerCase(),
                outcome == null ? "null" : "\"" + Request.escape(outcome) + "\"");
    }
}
