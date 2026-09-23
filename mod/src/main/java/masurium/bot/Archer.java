package masurium.bot;

import masurium.common.Logbook;
import masurium.common.Misses;
import masurium.common.Request;
import masurium.common.Route;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

/**
 * Killing with arrows. The bow is ITS weapon, and that is the whole difference.
 *
 * <p>The bow is only for killing, not for hunting. That is why this is a separate class
 * and not a branch of the {@link Hunter}: hunting is harvesting (chasing with a sword and
 * picking up what drops), killing is taking down a specific mob from afar. Hunting does
 * not touch the bow, and this does not pick up what the dead mob drops.
 *
 * <p>Without a bow or arrows it refuses from the start, and if the quiver empties
 * mid-errand it gives up and says so; it does not finish with the sword, because that
 * would be hunting by another name. The one sword allowed is for what is already on top
 * of it, within {@value #HIT} blocks: standing still drawing the bow with the target one
 * step away looked absurd.

 * <p>And it does not insist on what it cannot hit: {@value #ARROW_PATIENCE} arrows that do
 * not lower a target's health and that target is left alone (see {@link Misses}), because
 * behind that number there is almost always a block eating the shots. It gets arrows again
 * if it hurts the bot, which proves it can be reached.
 *
 * <p>It does not live in the {@link Guard} either, for the usual reason: shooting from
 * afar is attacking on an errand, and the guard only answers what is already on top of
 * it.
 *
 * <p>And after releasing, IT STAYS TO WATCH THE SHOT: while the arrow flies it neither
 * draws another nor starts walking. Otherwise it walked towards the target with the
 * projectile still in the air, and waiting also keeps the miss count honest: each arrow
 * is judged against the prey's health when it was released, not against the previous
 * shot's.
 */
final class Archer {

    /** The shot reaches this far; farther, it walks to get in range. */
    private static final double SHOT_MAX = 25.0;
    /**
     * Target search radius: the maximum possible; if it does not see them, it does not
     * see them. No artificial cap: 128 covers everything the server sends to the client
     * (tracking of most mobs ends much earlier), and that is the real limit, not a number
     * of ours.
     */
    private static final double VIEW = 128.0;
    /** Ticks between replans when looking for an angle. */
    private static final int EVERY = 10;
    /** Ticks without seeing the target before giving up. */
    private static final int PATIENCE = 200;
    /** With less health than this the errand is dropped: survival rules. */
    private static final float HP_MIN = 6.0f;
    /**
     * Cap on kills per numbered errand. Unlimited (count=0) exists because "kill ALL the
     * villagers you see" kept hitting the 8 and the errand had to be repeated over and
     * over.
     */
    static final int COUNT_MAX = 8;
    /** At this distance no bow will do: finish it with the sword. */
    private static final double HIT = 3.0;
    /** Inside this distance it does not draw: the target is already on top of it. */
    private static final double SWORD = 10.0;
    /**
     * Arrows in a row without lowering the prey's health before assuming it is under
     * cover and switching to the sword. Mob health is synced to the client, so the real
     * damage is measured, not the gesture.
     */
    private static final int ARROW_PATIENCE = 3;
    /**
     * Cap on ticks given to an arrow to arrive. The normal time is computed from the
     * distance; this is the ceiling, so a lost shot does not leave the bot staring at the
     * horizon.
     */
    private static final int FLIGHT_MAX = 30;

    private final Walker walker;

    private String type;
    private int toKill;
    private int killed;
    private Entity prey;
    private boolean killing;
    private String outcome = "I am not shooting at anything";
    private int ticksWithoutSeeingHer;
    private int ticksSincePlan;
    private double blueprintX, blueprintZ;
    /** Killing without limit, ticks spent without seeing a new target. */
    private int ticksWithoutPrey;
    /** The prey's health when the arrow now flying was released. */
    private float hpWhenReleased;
    /** Arrows in a row that did not lower THIS prey's health. */
    private int arrowsWithoutDamage;
    /** There is an arrow in the air and its outcome is not known yet. */
    private boolean arrowInFlight;
    /** Ticks it has been flying, and the ones given to this particular shot. */
    private int flightTicks;
    private int shotWait;
    /** Accumulated ticks without finding a path to the current prey. */
    private int stuckTicks;
    /**
     * The tiles of the previous segment, made more expensive when replanning so it does
     * not zig-zag against a moving mob (the same anti-dithering as the Hunter).
     */
    private java.util.Set<Route.Point> footsteps;
    /**
     * Targets without a path, discarded by id. They get another chance with every kill
     * (they go in and out of houses) and at the start.
     */
    private final java.util.Set<Integer> unreachable = new java.util.HashSet<>();

    Archer(Walker walker) {
        this.walker = walker;
    }

    /** @return null if the errand started, or the reason if not */
    synchronized String begin(String type, int quantity) {
        String t = type == null ? "" : type.toLowerCase();
        if (t.contains("breeze") || t.contains("enderman")) {
            return "at " + t + " one does not shoot: the breeze deflects arrows and the "
                    + "enderman teleports. That is by sword, with kill";
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return "I am in no world";
        if (!p.isAlive()) return "I am dead; I must respawn first";
        String withoutBow = Bow.ready(p);
        if (withoutBow != null) return withoutBow;

        this.type = type;
        // count <= 0 = NO LIMIT, like hunting: "kill ALL the villagers you see" is not a
        // number, it is until none are left.
        this.toKill = quantity <= 0 ? Integer.MAX_VALUE
                : Math.min(COUNT_MAX, quantity);
        this.killed = 0;
        this.ticksWithoutPrey = 0;
        this.unreachable.clear();
        this.prey = searchPrey(mc, p);
        if (prey == null) {
            return String.format("I see no %s within %d blocks",
                    type, (int) VIEW);
        }
        newPrey();
        this.killing = true;
        this.outcome = null;
        this.ticksWithoutSeeingHer = 0;
        this.ticksSincePlan = EVERY;
        this.blueprintX = Double.NaN;
        Logbook.note("archery", toKill == Integer.MAX_VALUE
                ? "going out to kill " + type + " with arrows, no limit"
                : String.format("going out to kill %d %s with arrows",
                        toKill, type));
        return null;
    }

    synchronized void stop(String because) {
        if (killing) {
            Logbook.note("archery", "I drop the bow: " + because);
            // Release key and draw right here: stopped mid-draw, the tick will not run to
            // do it, and a use key left pressed reuses whatever is in hand on its own.
            LocalPlayer p = Minecraft.getInstance().player;
            if (p != null) Bow.leave(p);
        }
        arrowInFlight = false;
        killing = false;
        prey = null;
        outcome = because;
    }

    /**
     * Finishes ON ITS OWN: like {@link #stop}, but notifying the brain.
     *
     * <p>Without a notice there is no turn, and a job that ends silently leaves the bot
     * standing and mute until someone asks: it had killed the target it was asked for
     * five minutes earlier, the outcome stayed in the state and the logbook, and nobody
     * found out: neither the brain, to tell it or carry on, nor the player. It is the
     * same lesson the {@link Fisher} learned with the broken rod.
     *
     * <p>The key carries the whole outcome: two different endings are two notices, and
     * the previous one's cooldown does not swallow them.
     *
     * <p>Only for endings that come ON THEIR OWN. When it stops because it was told to
     * ({@code /stop}, a new trip, another behaviour claiming the body) there is already a
     * turn under way and the notice would be redundant.
     */
    private void finish(String because) {
        stop(because);
        Needs.warn("archery_done:" + because,
                "I finished the bow errand: " + because);
    }

    synchronized void tick() {
        if (!killing) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) { stop("I left the world"); return; }
        if (!p.isAlive()) { stop("I was killed while shooting"); return; }
        if (p.getHealth() < HP_MIN) {
            finish(String.format("I have very little hp left (%.1f); I have %d of %d",
                    p.getHealth(), killed, toKill));
            return;
        }
        // The bow is the errand's weapon: without it, there is no errand. No finishing
        // with the sword: that would be hunting by another name.
        String withoutBow = Bow.ready(p);
        if (withoutBow != null) {
            walker.stop("no bow");
            finish(String.format("%s; I have %d of %d",
                    withoutBow, killed, toKill));
            return;
        }

        // Is the current target still standing?
        if (prey == null || !prey.isAlive()) {
            Bow.leave(p);          // do not keep drawing at a dead one
            if (prey != null) {
                killed++;
                Logbook.note("archery", toKill == Integer.MAX_VALUE
                        ? String.format("I took down %s with arrows (%d)",
                                type, killed)
                        : String.format("I took down %s with arrows (%d of %d)",
                                type, killed, toKill));
                prey = null;
                unreachable.clear();   // another chance for the ones in the house
            }
            if (killed >= toKill) {
                walker.stop("errand fulfilled");
                finish(String.format("I killed %d %s with arrows; what they dropped "
                        + "stayed on the ground", killed, type));
                return;
            }
            prey = searchPrey(mc, p);
            if (prey == null) {
                // Without a limit it waits a while in case another one appears in sight;
                // with a fixed number it finishes and says how it went.
                if (toKill == Integer.MAX_VALUE) {
                    if (++ticksWithoutPrey < 600) return;
                    walker.stop("no targets");
                    finish(String.format("I killed %d %s with arrows; half a "
                            + "minute without seeing more", killed, type));
                    return;
                }
                walker.stop("no targets");
                finish(String.format("I killed %d of %d %s; I see no more nearby",
                        killed, toKill, type));
                return;
            }
            ticksWithoutPrey = 0;
            blueprintX = Double.NaN;
            newPrey();
        }

        if (prey.isRemoved()) {
            if (++ticksWithoutSeeingHer > PATIENCE) {
                finish(String.format("I lost sight of the %s; I have %d of %d",
                        type, killed, toKill));
            }
            return;
        }
        ticksWithoutSeeingHer = 0;

        double d = p.distanceTo(prey);

        // When the target is relatively close the bot does not keep drawing still. The
        // bow is the ERRAND's weapon, but a mob on top of you is finished in melee, as
        // anyone with two hands would; what stays forbidden is hunting with the sword,
        // not defending with it.
        if (d <= HIT) {
            arrowInFlight = false;
            Bow.leave(p);
            if (walker.walking()) walker.stop("prey on top of me");
            hit(mc, p, prey);
            return;
        }

        // An arrow in the air is watched to the end. Before, it released and in the same
        // tick went back to the usual (draw again or start walking), and from outside it
        // looked like it abandoned the shot halfway, advancing towards the target with
        // the projectile still in the air. Waiting also fixes the MEASUREMENT: each arrow
        // is judged on its own, against the prey's health when it was released, instead
        // of dragging the count to the next shot.
        if (arrowInFlight) {
            if (d <= SWORD) {
                // It came at me while the arrow flew: the shot is not judged and no more
                // waiting; distance rules.
                arrowInFlight = false;
            } else {
                Bow.aim(p, prey);            // the eyes stay on the target
                if (prey instanceof LivingEntity viva
                        && viva.getHealth() < hpWhenReleased - 0.01f) {
                    arrowInFlight = false;
                    arrowsWithoutDamage = 0;
                    Logbook.note("archery", String.format(
                            "I hit the %s; it has %.0f hp left",
                            type, viva.getHealth()));
                    return;
                }
                if (++flightTicks < shotWait) return;
                arrowInFlight = false;
                arrowsWithoutDamage++;
                Logbook.note("archery", String.format(
                        "I missed the shot at the %s at %d blocks (%d in a row)",
                        type, (int) d, arrowsWithoutDamage));
                if (arrowsWithoutDamage >= ARROW_PATIENCE) {
                    // Almost always a block eating the shots. Insisting only gives
                    // arrows away, so this one is left alone until it hurts me.
                    Logbook.note("archery", String.format(
                            "%d arrows without harming the %s: I leave it alone until "
                            + "it hurts me", arrowsWithoutDamage, type));
                    Misses.giveUp(prey.getUUID(), hpWhenReleased);
                    unreachable.add(prey.getId());
                    prey = null;
                    arrowsWithoutDamage = 0;
                    Bow.leave(p);
                }
                return;
            }
        }

        // The bow is for what is really far: from SWORD inwards it runs to finish it.
        if (d > SWORD && d <= SHOT_MAX
                && p.hasLineOfSight(prey)) {
            if (walker.walking()) walker.stop("target in range");
            stuckTicks = 0;
            shoot(mc, p, prey);
            return;
        }
        Bow.leave(p);   // close, under cover or out of range: the legs

        // Get in range or recover the line of sight, with the Hunter's restraint:
        // replanning every tick would burn the game thread.
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
        // Straight to the new navigation: goal "within 2 of the mob" (the path finder
        // picks the tile), partial routes for what is far, a 30 ms deadline, the previous
        // segment's tiles made more expensive, and building allowed: the loose leash
        // applies to shooting too.
        Route.Result r = Route.search(world, here,
                Route.Meta.near(aimTarget, 2.0),
                new Route.Options(MasuriumBot.safeFall(p.getHealth()), 8_000,
                        true, true, 30, footsteps)
                        .breaking(Preferences.is("break_to_advance")));
        // A single step = "I am already there": being on the goal ring without being able
        // to act is NOT progress (the prey one block away horizontally but three up,
        // inside its house, logged "arrived" 440 times with the bot frozen). That goes to
        // the stuck counter.
        if (r.hasRoute() && r.steps().size() > 1
                && walker.follow(r.steps(), x -> null) == null) {
            footsteps = new java.util.HashSet<>(r.steps());
            blueprintX = prey.getX();
            blueprintZ = prey.getZ();
            stuckTicks = 0;
            return;
        }
        // Far away, the direct route does not work (8k nodes): the FillWorker's protocol,
        // get close in X and Z to the foot of its column; the Y will be a local problem.
        if (d > 30 && MasuriumBot.approachTo(p, world, here,
                aimTarget.x(), aimTarget.z(), walker)) {
            blueprintX = prey.getX();
            blueprintZ = prey.getZ();
            stuckTicks = 0;
            return;
        }
        // Nowhere to stand near it and no path to it: the typical target is a villager
        // inside its house. Without this the bot stayed stopped FOREVER and silently,
        // retrying the route every 10 ticks (frozen for 53 s). After ~5 s stuck the
        // target is discarded with a note and another is sought; if none is reachable,
        // the errand ends saying so instead of hanging.
        stuckTicks += EVERY;
        if (stuckTicks >= 100) {
            Logbook.note("archery", String.format(
                    "I find no path to the %s; I discard it", type));
            unreachable.add(prey.getId());
            prey = null;
            stuckTicks = 0;
        }
    }

    /**
     * The bow cycle, one step per tick: wield it, draw, aim while charging and release at
     * full charge. Drawing is the {@code useItem} of the bite and of the bobber;
     * releasing is {@code releaseUsingItem}, and in between the eyes follow the mob every
     * tick: the arrow goes where the bot looks when releasing, not where it looked when
     * drawing.
     */
    private void shoot(Minecraft mc, LocalPlayer p, Entity who) {
        if (!Bow.drawAndRelease(mc, p, who)) return;
        double dist = p.distanceTo(who);
        Logbook.note("archery", String.format("arrow at %s at %d blocks",
                type, (int) dist));
        waitForArrow(who, dist);
    }

    /**
     * How long it keeps watching this shot. At full charge the arrow leaves at about
     * three blocks per tick and slows down, so 2.5 on average is prudent; the six extra
     * ticks are for the health data, which the server sends and takes a moment to arrive.
     * With the cap of {@value #FLIGHT_MAX} a lost shot does not leave it staring at the
     * horizon.
     */
    private void waitForArrow(Entity who, double dist) {
        arrowInFlight = true;
        flightTicks = 0;
        shotWait = Math.min(FLIGHT_MAX, (int) Math.ceil(dist / 2.5) + 6);
        hpWhenReleased = who instanceof LivingEntity viva
                ? viva.getHealth() : 0;
    }

    /** Per-prey counters: every new target starts a fresh damage measurement. */
    private void newPrey() {
        arrowsWithoutDamage = 0;
        stuckTicks = 0;
        footsteps = null;
        arrowInFlight = false;
        hpWhenReleased = prey instanceof LivingEntity viva
                ? viva.getHealth() : 0;
    }

    /**
     * The Guard's hit, to finish off: best weapon, charged to 90%, looking at the mob.
     */
    private void hit(Minecraft mc, LocalPlayer p, Entity who) {
        int weapon = WeaponPicker.bestWeapon(p);
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
     * The nearest target of the requested type. Players only if the {@code hunt_players}
     * preference is true, the same toggle as hunting: the key to "people may be attacked"
     * is a single one.
     */
    private Entity searchPrey(Minecraft mc, LocalPlayer p) {
        boolean pvp = Preferences.is("hunt_players");
        Entity best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity e : mc.level.getEntities(p,
                new AABB(p.blockPosition()).inflate(VIEW),
                x -> x instanceof LivingEntity && x.isAlive()
                     && (pvp || !(x instanceof Player)))) {
            if (!isTheType(e) || unreachable.contains(e.getId())
                    || !Misses.worthIt(e.getUUID())) {
                continue;     // what it could not hit is not chosen again either
            }
            double dist = p.distanceTo(e);
            if (dist < bestDist) {
                bestDist = dist;
                best = e;
            }
        }
        return best;
    }

    /**
     * The type id (cow, zombie, player...) or, for a player, also their NAME: the same
     * rule as the Hunter and for the same incident: "kill Player1" arrives with the name,
     * not with "player".
     */
    private boolean isTheType(Entity e) {
        if (BuiltInRegistries.ENTITY_TYPE.getKey(e.getType())
                .getPath().equals(type)) {
            return true;
        }
        // And the mob's NAME TAG, as in the Hunter.
        if (e.hasCustomName() && e.getCustomName().getString().strip()
                .toLowerCase().equals(type)) {
            return true;
        }
        return e instanceof Player pj
                && pj.getGameProfile().getName().toLowerCase().equals(type);
    }

    /**
     * If whoever it is chasing now is a PLAYER, their name; otherwise null. The guard
     * uses it so it does not complain about being hit when the bot started the fight.
     */
    synchronized String playerInCrosshair() {
        return prey instanceof net.minecraft.world.entity.player.Player pj
                ? pj.getGameProfile().getName() : null;
    }

    synchronized String state() {
        if (!killing) {
            return String.format("{\"killing\":false,\"outcome\":\"%s\"}",
                    Request.escape(outcome));
        }
        return String.format(
                "{\"killing\":true,\"type\":\"%s\",\"killed\":%d,\"of\":%d,"
                + "\"arrow_in_flight\":%b,\"arrows_without_damage\":%d}",
                Request.escape(type), killed, toKill,
                arrowInFlight, arrowsWithoutDamage);
    }
}
