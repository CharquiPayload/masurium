package marionette.bot;

import marionette.common.Misses;
import marionette.common.Phrases;
import marionette.common.Logbook;
import marionette.common.Request;
import marionette.common.Route;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Escorting someone: going with that person and hitting whatever comes near them.
 *
 * <p>An important function for more vulnerable players: the bot escorts a player (or an
 * entity) against enemies, warning when a creeper gets close to the escorted one.
 *
 * <p>The difference from {@link Follower} is not the feet (the feet are the same, this
 * rests on it) but the eyes: following is going behind, escorting is watching what
 * approaches the OTHER person and getting ahead of it. And escorting is a STATE: if the
 * feet go off to something else for a moment (gathering wood, crafting an axe), it comes
 * back when done. Only the orders that truly cancel it cancel it. The difference from the
 * {@link Guard} is that the guard only answers what already hit the bot itself; here it
 * hits first, for someone else.
 *
 * <p>A creeper is not hit, it is WARNED about. It is the one piece not solved by hitting:
 * approaching a creeper heading for another person would put the explosion together with
 * both of them. It is said in the chat, right away and with the distance, which is what
 * helps whoever is escorted.
 */
final class Escort {

    /** How far around the escorted one it watches. */
    private static final double WATCH = 16.0;
    /**
     * At this distance from ME a hostile gets hit: it is the real reach of the arm, not a
     * design decision.
     */
    private static final double REACH = 3.0;
    /** A creeper closer than this to the escorted one already deserves a warning. */
    private static final double CREEPER_NOTICE = 16.0;
    /** Ticks between checks. Hitting is measured in ticks; watching is not. */
    private static final int EVERY = 5;
    /**
     * A hostile this close to the escorted one or closer: go AFTER IT, with the sword. It
     * started at 5 and went up after testing: leaving at five means leaving when the
     * zombie has already arrived and hurt them. At eight there is time to intercept it
     * first.
     *
     * <p>It is also the lower limit of the bow, for the usual reason: an arrow that
     * misses a mob stuck to someone ends up in that someone.
     */
    private static final double INTERCEPT = 8.0;
    /** From here on the bow is fine, up to WATCH. */
    /**
     * Closer than this there is no shooting: charge with the sword. Not so much bow, and
     * not from so close.
     */
    private static final double SHOT_MIN = 10.0;
    /**
     * How far it may stray from the escorted one to go after something: the leash. It
     * grows with INTERCEPT, since deciding to go out at eight is useless if the leash
     * does not reach. Escorting is staying; chasing would be leaving.
     */
    private static final double LEASH = 12.0;
    /** Ticks between replans when going after a mob. */
    private static final int PLAN_EVERY = 10;
    /** Without seeing the escorted one for this long (ms), they are considered gone. */
    private static final long UNSEEN_PATIENCE = 30_000;
    /** Farther than this from the person, the march resumes after a detour. */
    private static final double RETURN_TO_HER_SIDE = 5.0;
    /**
     * Ticks without the attacker touching the escorted one again before letting it go (30
     * s): a wolf insists, but it does not chase for an hour someone who hit once and
     * left.
     */
    private static final int ATTACKER_PATIENCE = 600;

    private final Follower follower;
    private final Walker walker;

    private String toWhom;
    private boolean escorting;
    private String outcome = "I am not escorting anyone";
    private int ticks;
    private int ticksSincePlan;
    private int ticksWithoutSeeingIt;
    /**
     * Like a wolf: whoever hit the escorted one, until it dies, moves away or goes a
     * while without touching them. It rules over what merely approaches: attack
     * everything that hurts the escorted one while escorting, like a wolf.
     */
    private Entity attacker;
    private int ticksWithoutDamageToEscorted;

    Escort(Follower follower, Walker walker) {
        this.follower = follower;
        this.walker = walker;
    }

    /** @return null if it started, or the reason it did not */
    synchronized String begin(String player) {
        // The feet come from the follower: it is exactly the same problem already solved
        // (keep the distance, do not cut in, replan). Without the follower's loss notice:
        // the escort has its own (more patient, and through the default escort when it is
        // left free).
        String negative = follower.begin(player, false);
        if (negative != null) return negative;
        this.toWhom = player;
        this.escorting = true;
        this.outcome = null;
        this.ticks = 0;
        this.ticksWithoutSeeingIt = 0;
        this.attacker = null;
        this.ticksWithoutDamageToEscorted = 0;
        Logbook.note("escort_status", "escorting " + player);
        People.tally(player, "escorts");
        Diary.note("escorting " + player);
        return null;
    }

    synchronized void stop(String because) {
        if (escorting) {
            Logbook.note("escort_status", "I drop the escort: " + because);
            LocalPlayer p = Minecraft.getInstance().player;
            if (p != null) Bow.leave(p);   // no draw nor key pending
        }
        escorting = false;
        attacker = null;
        outcome = because;
    }

    synchronized void tick() {
        if (!escorting) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) { stop("I left the world"); return; }
        if (!p.isAlive()) { stop("I was killed while escorting"); return; }
        if (++ticks < EVERY) return;
        ticks = 0;

        Entity who = player(mc, toWhom);
        if (who == null) {
            // It may be a blink (a chunk change, a tunnel) or they may really have left.
            // Half a minute is given before considering them gone. In TICKS x 50 ms:
            // comparing ticks with milliseconds turned the half minute into 25 minutes,
            // the guard stayed "escorting" nobody and the default escort (which notifies
            // the brain) never kicked in.
            if (++ticksWithoutSeeingIt * EVERY * 50L > UNSEEN_PATIENCE) {
                stop("I no longer see " + toWhom + " anywhere");
            }
            return;
        }
        ticksWithoutSeeingIt = 0;
        // Whatever hits the one I escort, whatever it is, is my enemy.
        aimAttackerOf(p, who);

        // Escorting is a STATE, not a walk. If the feet went off to something else
        // (gathering the wood, crafting an axe, digging a block) the escort does NOT end:
        // it is resumed when that finishes. Otherwise, after crafting the axe it forgot
        // it was escorting. Only the orders that truly cancel it cancel it.
        if (!follower.following()) {
            if (!walker.walking()
                    && p.distanceTo(who) > RETURN_TO_HER_SIDE) {
                if (follower.begin(toWhom, false) == null) {
                    Logbook.note("escort_status",
                            "I finished the other thing; back to escorting " + toWhom);
                }
            }
            return;      // without feet there is no watching: first back to their side
        }

        // While eating the hand is not switched: each "prepare weapon" cuts the bite and
        // eating starts over five seconds later (a hungry guard with six cakes had its
        // hand switched to stone_sword mid-bite, every time). A bite lasts a second and a
        // half; if fighting is really needed now, low health rules (and the Guard keeps
        // answering hits).
        if (MarionetteBot.eating() && p.getHealth() >= 8.0f) return;

        Entity creeper = null;
        Entity hostile = null;
        double nearer = Double.MAX_VALUE;
        for (Entity e : mc.level.getEntities(p,
                new AABB(who.blockPosition()).inflate(WATCH),
                x -> x instanceof Enemy && x.isAlive())) {
            double toEscorted = e.distanceTo(who);
            if (e instanceof Creeper) {
                if (toEscorted <= CREEPER_NOTICE
                        && (creeper == null
                            || toEscorted < creeper.distanceTo(who))) {
                    creeper = e;
                }
                continue;                        // NEVER hit the creeper
            }
            if (toEscorted < nearer) { nearer = toEscorted; hostile = e; }
        }
        // Whatever HITS rules over whatever only approaches, and it need not be one of
        // the usual hostiles: a wolf, a golem, a llama... or a player with
        // defend_from_players.
        if (attacker != null && attacker.isAlive()) hostile = attacker;

        if (creeper != null) {
            // A person is warned through the chat. The boss (another bot) is not: it sees
            // creepers just like I do, and warning it every fifteen seconds is noise for
            // everyone.
            if (!MarionetteBot.isMyBoss(toWhom)) {
                Voice.say("escort-creeper", 15_000, Phrases.of("escort_creeper",
                        toWhom, (int) creeper.distanceTo(who)));
            }
            Logbook.note("escort_status", String.format(
                    "I warn %s of a creeper %d blocks from them",
                    toWhom, (int) creeper.distanceTo(who)));
        }

        // Creepers get arrows: never hit or intercepted (that would bring the explosion
        // to both), but if there is no other hostile to deal with it is shot from a
        // distance with the bow brakes below, so the guard takes it down before it
        // arrives.
        boolean onlyBow = false;
        if (hostile == null && creeper != null) {
            hostile = creeper;
            onlyBow = true;
        }
        // The costly bow lesson: a use key left pressed reuses whatever is in hand on its
        // own. As soon as it is not time to shoot, it is released.
        if (hostile == null) { Bow.leave(p); return; }
        double toEscorted = hostile.distanceTo(who);
        double toMe = p.distanceTo(hostile);

        // 1. Within my reach: hit it, no more.
        if (!onlyBow && toMe <= REACH) { Bow.leave(p); hit(mc, p, hostile); return; }

        // 2. CHARGE: against any hostile ON FOOT in sight it goes after it with the
        //    sword, on the leash (no farther than LEASH from whoever it protects). The
        //    bow is for what cannot be charged: far away, flying or without a path. It
        //    should charge a zombie or a skeleton, not use the bow so much, nor from so
        //    close.
        boolean loadable = !onlyBow && hostile.onGround()
                && p.distanceTo(who) <= LEASH && toMe <= LEASH;
        if (loadable) {
            Bow.leave(p);
            if (walker.walking()) return;
            if (++ticksSincePlan < PLAN_EVERY) return;
            ticksSincePlan = 0;
            ClientWorld world = new ClientWorld(mc.level);
            Route.Point here = MarionetteBot.whereAmI(world, p);
            Route.Point where = new Route.Point(
                    (int) Math.floor(hostile.getX()),
                    (int) Math.floor(hostile.getY()),
                    (int) Math.floor(hostile.getZ()));
            Route.Result r = Route.search(world, here,
                    Route.Meta.near(where, 2.0),
                    new Route.Options(MarionetteBot.safeFall(p.getHealth()),
                            4_000, false, true).withDeadline(20));
            if (r.hasRoute() && r.steps().size() > 1) {
                walker.follow(r.steps(), x -> null);
                return;
            }
            // No path to it: let the bow try.
        }

        // 3. Far from the escorted one: there the bow is fine, which is what was missing
        //    for skeletons. With two brakes, both for the same reason: not putting an
        //    arrow into whoever I came to protect.
        Vec3 aimTarget = hostile.position().add(0, hostile.getBbHeight() * 0.5, 0);
        if (toMe < SHOT_MIN || toMe > WATCH
                || !Misses.worthIt(hostile.getUUID())   // three arrows, no damage: enough
                || Bow.ready(p) != null
                || Bow.immuneToArrows(hostile)     // breeze, enderman: with the sword
                || !p.hasLineOfSight(hostile)
                || blocksTheShot(p.getEyePosition(), aimTarget, who)
                || walker.walking()        // drawing while walking is missing
                || Bow.standingInWater(p)) {          // no shooting in water
            Bow.leave(p);
            return;
        }
        if (Bow.drawAndRelease(mc, p, hostile)) {
            Misses.arrow(hostile.getUUID(), healthOf(hostile));
            Logbook.note("escort_status", String.format(
                    "arrow at a %s %d blocks from %s",
                    BuiltInRegistries.ENTITY_TYPE.getKey(
                            hostile.getType()).getPath(),
                    (int) toEscorted, toWhom));
        }
    }

    /**
     * Does the escorted one cross the line of fire? Their position is projected onto the
     * shot line: if it falls WITHIN the segment and less than a meter and a half from it,
     * the arrow would graze them and there is no shot.
     *
     * <p>The real case is not odd geometry: it is the most normal thing in the world, the
     * person you protect standing between you and what you want to kill.
     */
    private static boolean blocksTheShot(Vec3 eyes, Vec3 aimTarget, Entity who) {
        Vec3 v = aimTarget.subtract(eyes);
        double length = v.length();
        if (length < 0.01) return false;
        Vec3 dir = v.scale(1 / length);
        Vec3 centerWho = who.position().add(0, who.getBbHeight() * 0.5, 0);
        // ABOVE the target (a phantom diving onto them), or right behind: the arrow that
        // hits grazes them and the one that misses keeps flying. That is how a guard once
        // killed its own boss. Three blocks of margin around the target, and the line
        // extended eight beyond.
        if (centerWho.distanceTo(aimTarget) < 3.0) return true;
        Vec3 toWhom = centerWho.subtract(eyes);
        double progress = toWhom.dot(dir);
        if (progress <= 0 || progress >= length + 8.0) return false;   // behind, or far beyond
        return toWhom.subtract(dir.scale(progress)).length() < 1.5;
    }

    /**
     * Who hit the escorted one, read from the hit itself: the client receives the source
     * of the last damage of every mob it sees (two seconds), and for an arrow that source
     * is whoever shot it, the same thing the {@link Lookout} does for itself. Not
     * creepers (those are warned about and shot, never approached), not my pets, and
     * players only with the defend_from_players preference (false by default). It is
     * released when it dies, moves away from the escorted one or goes half a minute
     * without touching them again.
     */
    private void aimAttackerOf(LocalPlayer p, Entity who) {
        if (who instanceof LivingEntity toIt) {
            DamageSource f = toIt.getLastDamageSource();
            Entity culprit = f == null ? null : f.getEntity();
            if (culprit != null && culprit.isAlive() && culprit != p
                    && culprit != who && !(culprit instanceof Creeper)
                    && !(culprit instanceof TamableAnimal t
                         && t.isTame() && t.isOwnedBy(p))
                    && (!(culprit instanceof Player)
                        || Preferences.is("defend_from_players"))) {
                if (culprit != attacker) {
                    Logbook.note("escort_status", String.format(
                            "a %s is hitting %s; going for it", toWhom,
                            nameOf(culprit)));
                }
                attacker = culprit;
                ticksWithoutDamageToEscorted = 0;
            }
        }
        if (attacker == null) return;
        ticksWithoutDamageToEscorted += EVERY;
        if (!attacker.isAlive() || attacker.distanceTo(who) > WATCH
                || ticksWithoutDamageToEscorted > ATTACKER_PATIENCE) {
            Logbook.note("escort_status", String.format("I leave the %s alone: %s",
                    nameOf(attacker), !attacker.isAlive() ? "it died"
                    : ticksWithoutDamageToEscorted > ATTACKER_PATIENCE
                        ? "it has gone half a minute without touching " + toWhom
                        : "it moved away"));
            attacker = null;
        }
    }

    private static String nameOf(Entity e) {
        return e instanceof Player pj ? pj.getGameProfile().getName()
                : BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
    }

    /** The usual hit: best weapon, charged to 90%, looking at the mob. */
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
        Logbook.note("escort_status", String.format("I push a %s away from %s",
                BuiltInRegistries.ENTITY_TYPE.getKey(who.getType()).getPath(),
                toWhom));
    }

    private static Entity player(Minecraft mc, String name) {
        for (Player j : mc.level.players()) {
            if (j.getGameProfile().getName().equalsIgnoreCase(name)) return j;
        }
        return null;
    }

    synchronized boolean escorting() {
        return escorting;
    }

    synchronized String state() {
        if (!escorting) {
            return String.format("{\"escorting\":false,\"outcome\":\"%s\"}",
                    Request.escape(outcome));
        }
        return String.format("{\"escorting\":true,\"to\":\"%s\"%s}",
                Request.escape(toWhom),
                attacker != null && attacker.isAlive()
                        ? ",\"defending_her_from\":\"" + Request.escape(nameOf(attacker)) + "\""
                        : "");
    }

    /**
     * The health of something alive, 0 for what has none. The miss count only needs a
     * number that goes down when an arrow lands.
     */
    private static float healthOf(Entity e) {
        return e instanceof LivingEntity alive ? alive.getHealth() : 0f;
    }

}
