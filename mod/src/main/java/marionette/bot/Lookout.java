package marionette.bot;

import marionette.common.Misses;
import marionette.common.Phrases;
import marionette.common.Logbook;
import marionette.common.Route;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.CrossbowAttackMob;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The one watching the perimeter, the sky and its own health: creepers, phantoms and
 * knowing when to stop working.
 *
 * <p>Excluding iron golems, the main causes of death of the bots are creepers and
 * phantoms. The {@link Guard} solved neither, for opposite reasons: it did not even see
 * phantoms (they fly, they were not {@code Monster}) and it treated a creeper like any
 * other mob, that is, staying next to it to hit it back, which is exactly how one dies
 * with a creeper.
 *
 * <p>Against creepers, three distances and none with the sword:
 * <ul>
 *   <li>from {@value #DANGER} blocks inwards, or if it is already swelling: legs. It
 *       backs away and says so in the chat: if the creeper is 10 blocks away or less,
 *       flee from it and call for help.</li>
 *   <li>between that and {@value #VIEW}, with line of sight: arrows. Shooting it does NOT
 *       light it (only proximity does), so the long shot is free of risk.</li>
 *   <li>beyond that, nothing: none of its business.</li>
 * </ul>
 *
 * <p>Against phantoms, the voice: it asks for permission to sleep and waits for an
 * answer. Sleeping skips the night for the whole server, so it is not the bot's decision;
 * but staying silent while they peck at it was not a decision either, it was not being
 * able to talk.
 *
 * <p>The BRAIN puts the words, not this class: these are body notices ({@link Needs}) and
 * up there they are told in each bot's own voice. When the bot backs away from a creeper,
 * a little delay in the sentence is fine. What cannot wait is the ESCAPE, and that does
 * not wait. The only sentence still canned is being cornered, because when it comes there
 * is nothing left to do but be heard.
 *
 * <p>And it also watches inwards: below {@value #BADLY_HURT} health and with something
 * hostile nearby, it drops whatever it is doing and backs away ({@link #retreat}). It is
 * the same number the Hunter already dropped its prey with, raised to every behaviour.
 *
 * <p>What it does NOT do: chase. Neither the fleeing creeper nor the departing phantom.
 * It is a lookout, not a huntress.
 */
final class Lookout {

    /** How far it looks for creepers: what the bow reaches. */
    private static final double VIEW = 25.0;
    /** From here inwards no shot will do. */
    private static final double DANGER = 10.0;
    /**
     * How far it retreats before facing it again with the bow. With margin over DANGER,
     * or it would spend its life going in and out.
     */
    private static final double SAFE = 16.0;
    /** Phantoms are looked for at this distance. They fly high and dive. */
    private static final double SKY = 24.0;
    /** After a phantom hit, how long it counts as "being attacked". */
    private static final long PHANTOM_ATTACKING_MS = 60_000;
    /** When a phantom last hit me (ms), or 0 if never. */
    private long lastPhantomHitMs;
    /** Ticks between replans of the escape. */
    private static final int EVERY = 10;
    /** How far whoever is shooting at you is chased. */
    private static final double DEFENSE = 25.0;
    /**
     * Up to here a mob counts even if it cannot be seen: at this distance a creeper
     * coming round the corner explodes all the same. Farther, only what is SEEN. Looking
     * at entities by distance goes through rock (a creeper in a nearby cave made the bot
     * afraid to go down, practically X-ray); looking by line of sight does not.
     */
    private static final double NEAR_BLIND = 4.0;
    /** From here inwards it hits; farther, bow or legs. */
    private static final double ARM = 3.0;
    /** From here the bow is fine for defending (closer, it finishes in melee). */
    /** Closer than this, the sword: a bow up close is slow and misses. */
    private static final double BOW_MIN = 10.0;
    /** Without a new hit in this time, it lets it be. */
    private static final int DEFENSE_PATIENCE = 20 * 15;
    /** Ticks between replans when going after whoever shoots. */
    private static final int DEFENSE_PLAN = 10;
    /**
     * The only thing still canned: being cornered. It is the one sentence of this class
     * that cannot arrive late, because when it arrives there is nothing left to do but be
     * heard.
     */
    private static final long CORNERED_NOTICE = 20_000;
    /**
     * Health below which it drops what it is doing and retreats. Three hearts: the same
     * number the Hunter drops its prey with, and for the same reason.
     */
    private static final float BADLY_HURT = 6.0f;
    /**
     * How far it looks for hostiles while badly hurt. Farther than this they do not reach
     * it before it is gone.
     */
    private static final double HARASSMENT = 12.0;
    /**
     * How far it runs in one go when fleeing. It used to be SAFE's 16 and, with partial
     * routes, it ended at nine: the zombie caught up walking. Fleeing is not dying, so it
     * really runs.
     */
    private static final double FLEE_FAR = 40.0;
    /**
     * And it does not stop fleeing until NOTHING hostile is left within this. Regaining
     * health on the way is not being safe.
     */
    private static final double NO_HARASSMENT = 24.0;

    /**
     * Air left before dropping everything and surfacing. Out of 300 (fifteen seconds), at
     * 100 five are left: plenty to get out of any reasonable place, and too little to
     * keep pushing its luck.
     */
    private static final int AIR_LOW = 100;

    /**
     * And how much air to recover before going back to its business. With hysteresis on
     * purpose: letting go right at the trigger means going back down the next second, and
     * it would never get out of the water.
     */
    private static final int AIR_ENOUGH = 250;

    private final Walker walker;

    private boolean fleeing;
    private int ticksSincePlan;
    private boolean retreating;
    /**
     * A creeper seen and in range this tick, to shoot it AFTER getting rid of whoever is
     * hitting me.
     */
    private Entity creeperInRange;
    private int ticksSinceRetreatPlan;
    private boolean breathing;
    /** Who is shooting at it from afar, and how long since they last touched it. */
    private Entity attacker;
    private int ticksWithoutDamage;
    private int ticksSinceDefensePlan;
    private float previousHp = -1;
    /**
     * Creepers that do NOT count, and until when (ms). Creepers in neighbouring,
     * unconnected caves gave a bot a panic attack. Two entries: the ones with no path on
     * foot to me (30 s, checked again), and the ones that already scared me three times
     * in a minute and a half without reaching me (90 s): fleeing from what does not
     * arrive is the panic attack.
     */
    private final java.util.Map<Integer, Long> ignoredCreepers = new java.util.HashMap<>();
    /**
     * Last answer to "can it reach me?" per creeper: [until ms, 0/1]. One path every 2 s
     * per creeper, not one per tick.
     */
    private final java.util.Map<Integer, long[]> recentReach = new java.util.HashMap<>();
    /** When each creeper scared me (ms). */
    private final java.util.Map<Integer, java.util.ArrayDeque<Long>> scares = new java.util.HashMap<>();
    private static final long IGNORE_NO_PATH_MS = 30_000;
    private static final long IGNORE_HEAVY_MS = 90_000;
    private static final int SCARES_MAX = 3;
    private static final long SCARES_WINDOW_MS = 90_000;
    /** Swelling this close, the path does not matter: the explosion goes through. */
    private static final double EXPLOSION = 7.0;

    /**
     * How often, in ticks, archers aiming at me are looked for (without waiting for the
     * hit).
     */
    private static final int EVERY_ARCHERS = 10;
    private int archerTicks;

    Lookout(Walker walker) {
        this.walker = walker;
    }

    /**
     * @return true if this is an emergency and it keeps the tick: the other behaviours
     *         are skipped, so nobody fights it for the legs while it backs away from a
     *         creeper
     */
    boolean tick() {
        drewThisTick = false;
        emergency = lookoutTick();
        // Whichever branch the tick left through: if the bow was left drawing and nobody
        // drew it this tick, it is released HERE.
        if (drawing && !drewThisTick) releaseBow();
        return emergency;
    }

    /**
     * What the last {@link #tick} returned. For whoever runs BEFORE the lookout in the
     * same tick (the automatic bite) and must not get in its way.
     */
    boolean inEmergency() {
        return emergency;
    }

    /**
     * Whether it is FLEEING a creeper. Different from retreating hurt: there hitting back
     * is fine, here it is exchanging blows next to a bomb (a bot chose to fight a zombie
     * instead of fleeing the creeper).
     */
    boolean fleeingACreeper() {
        return fleeing;
    }

    private boolean emergency;

    /**
     * Whether the last tick left the bow drawing: the faked use key is still pressed and
     * must be released on ANY exit, not only on the ones that remembered to.
     *
     * <p>A zombie left a fishing bot at 5 health; it retreated, and as soon as it
     * regained health it answered with the bow. When the zombie died (it was daytime),
     * {@link #defend} set {@code attacker} to null and returned false without releasing
     * the key, and with the key pressed the client keeps drawing forever (the bow admits
     * 72000 ticks of charge). The trip the brain had requested meanwhile went on as if
     * nothing happened, at a fifth of the speed: the bot walked to a destination with its
     * bow drawn. The same happened if the attacker moved beyond {@value #DEFENSE}, if
     * {@link #aimAttacker} forgot it out of patience, or if the retreat (which runs
     * BEFORE and keeps the tick) kicked in mid-draw. Archer and Escort already released
     * it in their {@code stop}; the Lookout has no stop, so it is solved at the end of
     * every tick, once.
     */
    private boolean drawing;
    /** Whether a draw (or shot) happened here in THIS tick. */
    private boolean drewThisTick;
    /** Ticks in a row drawing the same bow. */
    private int drawingTicks;
    /**
     * Hard cap: 5 s. The shot leaves at {@value Bow#LOAD_JOB} ticks, so reaching this
     * means something keeps resetting the use counter; insisting means walking at a fifth
     * of the speed aiming at nothing. The bow should not be aimed for more than 5
     * seconds.
     */
    private static final int DRAW_MAX = 20 * 5;

    /**
     * The bow, from this class, ALWAYS goes through here: that way {@link #tick} knows
     * whether to release it when the branch that drew it no longer runs.
     */
    private boolean draw(Minecraft mc, LocalPlayer p, Entity who) {
        drewThisTick = true;
        if (++drawingTicks > DRAW_MAX) {
            Logbook.note("danger", String.format(
                    "I have spent %d s with the bow drawn unable to shoot: I "
                    + "release it", DRAW_MAX / 20));
            releaseBow();
            return false;
        }
        boolean exited = Bow.drawAndRelease(mc, p, who);
        drawing = !exited;
        if (exited) drawingTicks = 0;
        return exited;
    }

    private void releaseBow() {
        drawing = false;
        drawingTicks = 0;
        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null) Bow.leave(p);
        else Minecraft.getInstance().options.keyUse.setDown(false);
    }

    private boolean lookoutTick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || !p.isAlive()) {
            fleeing = false;
            // The jump key is released HERE too: drowning with the key held left the bot
            // hopping on land after respawning.
            if (breathing) {
                breathing = false;
                mc.options.keyJump.setDown(false);
            }
            return false;
        }

        // First of all, ahead even of the creeper: under water and without air there is
        // nothing else to do, and the fifteen seconds run whether or not someone is on
        // top of it.
        if (breathe(mc, p)) return true;

        aimAttacker(p);

        Entity creeper = nearest(mc, p, VIEW,
                e -> e instanceof Creeper && inSight(p, e) && canReachMe(mc, p, e));
        if (creeper != null) {
            double d = p.distanceTo(creeper);
            boolean enabled = creeper instanceof Creeper c
                    && c.getSwellDir() > 0;
            // Swelling 25 blocks away is SOMEONE ELSE's scare (a creeper exploding near a
            // player 20-30 blocks away kept a guard fleeing and warning every three
            // seconds). Only if it is in range. With the bow in range it SHOOTS, it does
            // not flee: fleeing while aiming threw the shot away, and a guard that flees
            // protects nobody (a guard must not be afraid of creepers). It only flees if
            // the creeper is too close for the bow, already swelling on top of it, or
            // without a ready bow or without seeing it.
            boolean inRange = Preferences.is("shoot_creepers")
                    && Misses.worthIt(creeper.getUUID())
                    && d >= BOW_MIN && !(enabled && d <= EXPLOSION)
                    && Bow.ready(p) == null && p.hasLineOfSight(creeper)
                    && !someoneInTheLine(mc, p, creeper) && !Bow.standingInWater(p);
            if (inRange) {
                if (fleeing) {
                    fleeing = false;
                    Logbook.note("danger", "creeper in range: I stop fleeing and shoot");
                }
                if (walker.walking()) walker.stop("I shoot the creeper");
                shootingUntil = System.currentTimeMillis() + 1_500;
                if (draw(mc, p, creeper)) {
                    Misses.arrow(creeper.getUUID(), healthOf(creeper));
                    Logbook.note("danger", String.format(
                            "arrow at the creeper at %d blocks", (int) d));
                }
                return true;
            }
            if (d <= DANGER || (enabled && d <= SAFE)) {
                // Up close there is no "one scare too many": flee.
                if (fleeing || d <= EXPLOSION || !extraScare(creeper, d)) {
                    flee(mc, p, creeper, d, enabled);
                    return true;
                }
                // Third scare from the same one without touching me: ignore it for a
                // while and carry on as if it were not there.
            }
            if (fleeing && d >= SAFE) {
                fleeing = false;
                if (walker.walking()) walker.stop("I am far away now");
                Logbook.note("danger", String.format(
                        "the creeper is %.0f blocks away: I am far away now", d));
            }
            // The SHOT at the creeper is no longer taken here: it is stored and goes
            // after the defense. With a zombie biting and a creeper in range, this kept
            // the tick and the bot was bitten to death. Fleeing a creeper does come
            // first: an explosion kills at once; an arrow at the creeper can wait until I
            // get rid of whoever is on top of me.
            creeperInRange = creeper;
            return fleeing;
        }
        if (fleeing) {
            fleeing = false;
            if (walker.walking()) walker.stop("the creeper is gone");
        }
        creeperInRange = null;

        // BEFORE defending, on purpose: going towards whoever shoots you with two hearts
        // is the fastest way to lose both.
        if (retreat(mc, p)) return true;

        if (defend(mc, p)) return true;

        // Now yes: nobody is hitting me, so the creeper left in range gets an arrow.
        if (creeperInRange != null && creeperInRange.isAlive()
                && Preferences.is("shoot_creepers")
                && Misses.worthIt(creeperInRange.getUUID())
                && Bow.ready(p) == null && p.hasLineOfSight(creeperInRange)
                && !Bow.standingInWater(p)) {
            if (walker.walking()) walker.stop("creeper in range");
            shootingUntil = System.currentTimeMillis() + 1_500;
            if (draw(mc, p, creeperInRange)) {
                Misses.arrow(creeperInRange.getUUID(), healthOf(creeperInRange));
                Logbook.note("danger", String.format("arrow at the creeper at "
                        + "%d blocks", (int) p.distanceTo(creeperInRange)));
            }
            return true;
        }

        phantoms(mc, p);
        return false;
    }

    /**
     * Badly hurt and with something hostile nearby: drop whatever it is and back away.
     *
     * <p>The {@link Hunter} already knew how to give up below {@value #BADLY_HURT}
     * health, but the Miner and the FillWorker did not: they kept digging with two hearts
     * until something finished them off. This raises that rule to ALL behaviours at once,
     * because while it lasts the Lookout keeps the tick and the others wait their turn.
     *
     * <p>It is a PAUSE, not a cancellation. It cancels nobody's errand and does not touch
     * their state: it only keeps the legs. When health returns, the job goes on where it
     * was without having to repeat it.
     *
     * <p>The trigger has TWO conditions on purpose. Low health alone is not an emergency
     * (digging with two hearts in an empty mine kills nobody, and a bot that stops to
     * wait for food it does not have stays stopped forever). What kills is low health
     * WITH something that hits: someone already hitting you, or something hostile within
     * {@value #HARASSMENT} blocks.
     *
     * <p>Creepers do not count here: the branch above handles those, runs earlier and
     * knows more (distances, lit fuse, bow).
     *
     * <p>With WHOEVER is hitting you, distance does not count. The harassment radius is
     * meant for what hits up close; a skeleton shoots from fifteen blocks and stays
     * there, outside the radius, so against an archer the retreat NEVER triggered. A bot
     * died of that, unarmed, shot to death without moving away: it should flee from the
     * attacker when about to die. The attacker comes from {@link #aimAttacker}, which
     * reads the source of the last hit: for an arrow, that source is whoever shot it.
     *
     * @return true if this is an emergency and it keeps the tick
     */
    private boolean retreat(Minecraft mc, LocalPlayer p) {
        // POISON also triggers retreating even with health left: it does not kill by
        // itself (it leaves half a heart), but it keeps dropping while you fight and
        // leaves the finishing blow to anyone.
        if (!Preferences.is("retreat_when_hurt")) {
            retreating = false;
            return false;
        }
        boolean poisoned = p.hasEffect(MobEffects.POISON);
        // Once fleeing, it flees UNTIL IT LOSES THEM. Before, it was enough for health to
        // rise above BADLY_HURT to drop the escape, and since it eats and regenerates
        // while walking, that happened within seconds: it stopped fleeing with the zombie
        // still behind, which caught up, and it started over. That was the loop.
        if (!retreating && p.getHealth() > BADLY_HURT && !poisoned) {
            return false;
        }
        // First whoever hits, at any distance within what the defense considers its own;
        // the harassment radius only for everything else (something hostile next to it
        // that has not touched it yet).
        Entity hostile = null;
        if (attacker != null && attacker.isAlive()
                && p.distanceTo(attacker) <= DEFENSE) {
            hostile = attacker;
        }
        if (hostile == null) {
            // Already fleeing it looks FARTHER and without requiring line of sight: a
            // zombie fifteen blocks away behind a tree is still coming.
            hostile = nearest(mc, p, retreating ? NO_HARASSMENT : HARASSMENT,
                    e -> e instanceof Enemy && !(e instanceof Creeper)
                         && (retreating || inSight(p, e)));
        }
        if (hostile == null) {
            if (retreating) {
                retreating = false;
                if (walker.walking()) walker.stop("nobody follows me any more");
                Logbook.note("danger", String.format(
                        "I have no hostiles within %d blocks any more: I stop fleeing "
                        + "(hp %.0f)", (int) NO_HARASSMENT, p.getHealth()));
                Needs.warn("safe", String.format(
                        "I managed to shake them off: I have nothing hostile within %d "
                        + "blocks any more and I have %.0f hp left. I am at "
                        + "%d %d %d", (int) NO_HARASSMENT, p.getHealth(),
                        (int) p.getX(), (int) p.getY(), (int) p.getZ()));
            }
            return false;
        }

        if (!retreating) {
            retreating = true;
            ticksSinceRetreatPlan = EVERY;   // let it plan in this tick
            walker.stop("badly hurt");
            Logbook.note("danger", String.format(
                    "I have %.0f hp left and there is a %s %d blocks away: I retreat",
                    p.getHealth(), name(hostile), (int) p.distanceTo(hostile)));
            // In the brain's voice: the retreat is already under way (it is this very
            // call), so the sentence can afford the delay. And the health in the key
            // makes getting worse count as a new notice instead of being swallowed by the
            // previous one's cooldown.
            Needs.warn("badly_hurt:" + (int) p.getHealth(),
                    String.format("I have %.0f hp of 20 left and there is "
                            + "a %s %d blocks away, so I dropped what "
                            + "I was doing and I am moving away",
                            p.getHealth(), name(hostile),
                            (int) p.distanceTo(hostile)));
        }
        if (++ticksSinceRetreatPlan < EVERY) return true;
        ticksSinceRetreatPlan = 0;
        if (!moveAwayFrom(mc, p, hostile)) {
            Voice.say("cornered", CORNERED_NOTICE, Phrases.of("hurt_cornered"));
        }
        return true;
    }

    /**
     * Surfacing to breathe.
     *
     * <p>Nobody watched the air, here or anywhere else in the mod, and a bot drowned
     * crossing a lake on its way somewhere a thousand five hundred blocks away. Its
     * logbook shows the drowning as it was: two health per second, seven times, with
     * nobody hitting it. The fix has two halves: this one saves it; the other one ({@code
     * SWIM}/{@code DIVE} in Route) makes it unnecessary.
     *
     * <p>It rises with the faked jump key, which inside water is what makes you ascend.
     * It is the same lesson as the bow and the bite: the client listens to the KEY, not
     * to the action. And like every faked key, it has to be released on ALL exits or it
     * stays jumping on dry land.
     *
     * <p>Stopping the Walker is a pause, like the retreat: once the air is back, the trip
     * goes on. If the only route goes under water it may keep going up and down without
     * progress; the stuck watcher sees that, and it speaks; drowning, on the other hand,
     * nobody saw.
     *
     * @return true if this is an emergency and it keeps the tick
     */
    private boolean breathe(Minecraft mc, LocalPlayer p) {
        int air = p.getAirSupply();
        if (!p.isUnderWater() || air > (breathing ? AIR_ENOUGH : AIR_LOW)) {
            if (breathing) {
                breathing = false;
                mc.options.keyJump.setDown(false);
                Logbook.note("danger", "I have air again");
            }
            return false;
        }
        if (!breathing) {
            breathing = true;
            walker.stop("no air");
            Logbook.note("danger", String.format(
                    "I have little air left (%d of 300): surfacing to breathe", air));
            Needs.warn("no_air", String.format(
                    "I am running out of air under water (%d of 300), "
                    + "so I dropped what I was doing to surface",
                    air));
        }
        mc.options.keyJump.setDown(true);
        return true;
    }

    /**
     * Who is hitting me from afar? The {@link Guard} only answers what is already next to
     * it, and against a skeleton that means never answering: it shoots from fifteen
     * blocks and stays there. A bot died to a skeleton: normally it should have shot
     * back, and without a bow it should have charged the skeleton (otherwise it will end
     * up killing it).
     *
     * <p>The culprit is named by the hit itself: for an arrow, the damage source points
     * to WHO shot it, not to the arrow. Creepers are discarded here: you flee from those,
     * you do not go towards them.
     */
    private void aimAttacker(LocalPlayer p) {
        float hp = p.getHealth();
        if (previousHp >= 0 && hp < previousHp - 0.01f) {
            var source = p.getLastDamageSource();
            Entity who = source == null ? null : source.getEntity();
            if (who instanceof Phantom) {
                lastPhantomHitMs = System.currentTimeMillis();
            }
            if (who != null && who.isAlive() && who != p
                    && who instanceof Enemy && !(who instanceof Creeper)) {
                if (who != attacker) {
                    Logbook.note("defense",
                            "I am being hit by a " + name(who)
                            + "; going for it");
                }
                attacker = who;
                ticksWithoutDamage = 0;
                ticksSinceDefensePlan = DEFENSE_PLAN;   // replan right away
            }
        }
        previousHp = hp;
        if (attacker != null && ++ticksWithoutDamage > DEFENSE_PATIENCE) {
            attacker = null;      // it has not touched me for a while: back to my business
        }
        if (attacker == null) archerAiming(p);
    }

    /**
     * Getting ahead of the first arrow. Waiting for the hit to know who shoots had two
     * holes: a skeleton that misses does not exist for the bot until it hits, and a
     * pillager hits so hard (3 to 5 per bolt) that at the second impact the bot is
     * already at the retreat threshold, so it was seen fleeing without ever answering,
     * and standing still with low health without retreating while nobody touched it. Here
     * whatever shoots and is in attack stance (skeletons, pillagers, any mob with a bow
     * or crossbow) in sight and in range is looked for, and taken as the attacker before
     * it hits. With that, the low-health retreat finds out too.
     */
    private void archerAiming(LocalPlayer p) {
        if (++archerTicks < EVERY_ARCHERS) return;
        archerTicks = 0;
        Minecraft mc = Minecraft.getInstance();
        Entity best = null;
        double mejorD = Double.MAX_VALUE;
        for (Mob m : mc.level.getEntitiesOfClass(Mob.class,
                p.getBoundingBox().inflate(DEFENSE))) {
            if (!(m instanceof Enemy) || m instanceof Creeper || !m.isAlive()) continue;
            boolean archer = m instanceof RangedAttackMob || m instanceof CrossbowAttackMob;
            if (!archer) continue;
            boolean attacking = m.isAggressive()
                    // The getter is not on the interface (and on Piglin it is private):
                    // Pillager.
                    || (m instanceof net.minecraft.world.entity.monster.Pillager pil && pil.isChargingCrossbow());
            if (!attacking) continue;
            double d = p.distanceTo(m);
            if (d > DEFENSE || d >= mejorD || !p.hasLineOfSight(m)) continue;
            best = m;
            mejorD = d;
        }
        if (best == null) return;
        attacker = best;
        ticksWithoutDamage = 0;
        ticksSinceDefensePlan = DEFENSE_PLAN;
        Logbook.note("defense", String.format("a %s is aiming at me from %.0f "
                + "blocks; I answer before it hits", name(best), mejorD));
    }

    /**
     * Answers whoever shoots: with the bow if there is one, and otherwise with legs and
     * arm. Going towards whoever shoots you looks like the opposite of defending and it
     * is the only thing that works: standing still fifteen blocks from a skeleton is
     * letting it kill you in turns.
     *
     * @return true if it keeps the tick
     */
    private boolean defend(Minecraft mc, LocalPlayer p) {
        if (attacker == null) return false;
        if (!attacker.isAlive()) { attacker = null; return false; }
        double d = p.distanceTo(attacker);
        if (d > DEFENSE) { attacker = null; return false; }

        // Within arm's reach none of this is needed: the Guard already hits, and it takes
        // care of looking and charging the hit itself.
        if (d <= ARM) { Bow.leave(p); return false; }

        // Never a bow against a WITCH: from afar she drinks potions and heals faster than
        // an arrow takes away, so nobody wins the ranged duel. Get on top of her.
        boolean witch = attacker instanceof Witch;
        if (!witch && !Bow.immuneToArrows(attacker) && d >= BOW_MIN && Bow.ready(p) == null
                && p.hasLineOfSight(attacker) && !someoneInTheLine(mc, p, attacker)
                && !Bow.standingInWater(p)) {
            if (walker.walking()) walker.stop("I answer with the bow");
            draw(mc, p, attacker);
            return true;
        }
        Bow.leave(p);

        // A FLYING attacker (phantom) is not chased along the ground: the route "to it"
        // was a single step already done and it replanned ten times a second (hundreds of
        // "arrived" and the TAB stuck on "stuck"). It stands its ground and hits with the
        // sword when the phantom comes down (the hit from above) or with the bow when
        // there is a shot.
        if (!attacker.onGround() && attacker.getY() > p.getY() + 1.5
                && !attacker.isInWater()) {
            if (walker.walking()) walker.stop("I do not chase a flyer");
            p.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,
                    attacker.position().add(0, attacker.getBbHeight() * 0.5, 0));
            return true;
        }
        // No bow (or no angle): get on top of it.
        if (walker.walking()) return true;
        if (++ticksSinceDefensePlan < DEFENSE_PLAN) return true;
        ticksSinceDefensePlan = 0;
        ClientWorld world = new ClientWorld(mc.level);
        Route.Point here = MarionetteBot.whereAmI(world, p);
        Route.Point where = new Route.Point((int) Math.floor(attacker.getX()),
                (int) Math.floor(attacker.getY()),
                (int) Math.floor(attacker.getZ()));
        Route.Result r = Route.search(world, here, Route.Meta.near(where, 2.0),
                new Route.Options(MarionetteBot.safeFall(p.getHealth()), 6_000,
                        true, true).withDeadline(30)
                        .breaking(Preferences.is("break_to_advance")));
        if (r.hasRoute() && r.steps().size() > 1) {
            walker.follow(r.steps(), x -> null);
            return true;
        }
        // Neither path nor shot: there is nothing to do from here, and keeping the tick
        // would block the other behaviours for nothing.
        attacker = null;
        return false;
    }

    /**
     * Backs away from the creeper in a straight line and asks for help. Without building
     * and without breaking: fleeing is for NOW, and an escape that stops to place
     * scaffolding is no escape.
     */
    private void flee(Minecraft mc, LocalPlayer p, Entity creeper,
                      double d, boolean enabled) {
        if (!fleeing) {
            fleeing = true;
            ticksSincePlan = EVERY;         // let it plan in this same tick
            walker.stop("creeper nearby");
            Logbook.note("danger", String.format(
                    "creeper %.0f blocks away%s: moving away",
                    d, enabled ? " and lit" : ""));
            // The brain TELLS it, in its own words: the escape does not wait for anyone
            // (that is this very method, already under way); only the sentence arrives
            // late, and a late sentence in its own voice is worth more than a punctual
            // canned one. The key carries the creeper's ID so two scares from two
            // creepers count as two notices and the cooldown does not merge them. It used
            // to carry the POSITION, and since the creeper moves every notice was a
            // "different" one and the ten-minute cooldown never rested: a notice (and a
            // chat line) every three seconds.
            Needs.warn("creeper:" + creeper.getId(),
                    String.format("a creeper came within %d blocks of me%s and I "
                            + "am moving away from it", (int) d,
                            enabled ? ", already swelling" : ""));
        }
        if (++ticksSincePlan < EVERY) return;
        ticksSincePlan = 0;
        if (!moveAwayFrom(mc, p, creeper)) {
            // Cornered. Saying it is all that is left, and it is much more than before:
            // dying in silence helps nobody.
            Voice.say("cornered", CORNERED_NOTICE, Phrases.of("creeper_cornered"));
        }
    }

    /**
     * Heads straight away from something, {@value #SAFE} blocks.
     *
     * <p>Shared by the creeper escape and the badly-hurt retreat: both want exactly the
     * same thing (put distance in between, now) and only differ in what triggers them and
     * what they say.
     *
     * @return false if there is no way out (cornered)
     */
    private boolean moveAwayFrom(Minecraft mc, LocalPlayer p, Entity of) {
        if (walker.walking()) return true;          // on my way

        double dx = p.getX() - of.getX();
        double dz = p.getZ() - of.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 0.01) { dx = 1; dz = 0; length = 1; }   // on top of me: does not matter
        int towardX = (int) Math.floor(p.getX() + dx / length * FLEE_FAR);
        int towardZ = (int) Math.floor(p.getZ() + dz / length * FLEE_FAR);

        ClientWorld world = new ClientWorld(mc.level);
        Route.Point here = MarionetteBot.whereAmI(world, p);
        // Goal by column: backing away is a matter of X and Z, height does not matter.
        // And with partial routes, because here half the way already helps: every block
        // of distance is damage not taken.
        Route.Result r = Route.search(world, here,
                Route.Meta.onlyXZ(towardX, towardZ),
                new Route.Options(MarionetteBot.safeFall(p.getHealth()), 4_000,
                        false, true).withDeadline(20));
        if (!r.hasRoute() || r.steps().size() <= 1) return false;
        walker.follow(r.steps(), x -> null);
        return true;
    }

    /**
     * Can THIS creeper reach me on foot? A creeper in the next cave, four blocks of stone
     * away, is seen (or so close that the {@link #NEAR_BLIND} blindness counts it as
     * seen) but does not arrive: fleeing from it is fleeing from nothing, and with two or
     * three like that around it is a panic attack. A path FROM the creeper TO me is
     * looked for, with a small budget (it is within 25 blocks) and a generous fall (a
     * creeper does not care about falling). No path: out for 30 seconds.
     *
     * <p>Swelling within {@value #EXPLOSION} it does not matter: the explosion needs no
     * path.
     */
    /**
     * Is ANOTHER player (my guard, my boss, anyone) on top of the target or in the line
     * of fire, extended eight blocks beyond? Then no shot: an arrow does not
     * discriminate. The same safety as the Escort, for its own bow (a guard once killed
     * its boss with an arrow).
     */
    static boolean someoneInTheLine(Minecraft mc, LocalPlayer p, Entity aimTarget) {
        Vec3 eyes = p.getEyePosition();
        Vec3 center = aimTarget.position().add(0, aimTarget.getBbHeight() * 0.5, 0);
        Vec3 v = center.subtract(eyes);
        double length = v.length();
        if (length < 0.01) return false;
        Vec3 dir = v.scale(1 / length);
        for (var other : mc.level.players()) {
            if (other == p || !other.isAlive()) continue;
            Vec3 c = other.position().add(0, other.getBbHeight() * 0.5, 0);
            if (c.distanceTo(center) < 3.0) return true;
            Vec3 a = c.subtract(eyes);
            double progress = a.dot(dir);
            if (progress <= 0 || progress >= length + 8.0) continue;
            if (a.subtract(dir.scale(progress)).length() < 1.5) return true;
        }
        return false;
    }

    private boolean canReachMe(Minecraft mc, LocalPlayer p, Entity e) {
        long now = System.currentTimeMillis();
        // Within explosion distance nothing else matters: ignored, without a path,
        // swelling or not. A bot died standing against a zombie with a creeper "ignored
        // as a nuisance" next to it: the anti-loop rule is for the distant scare, never
        // for the one on top of you.
        if (p.distanceTo(e) <= EXPLOSION) return true;
        Long until = ignoredCreepers.get(e.getId());
        if (until != null) {
            if (now < until) return false;
            ignoredCreepers.remove(e.getId());
        }
        if (e instanceof Creeper c && c.getSwellDir() > 0
                && p.distanceTo(e) <= EXPLOSION) {
            return true;
        }
        long[] recent = recentReach.get(e.getId());
        if (recent != null && now < recent[0]) return recent[1] == 1;

        boolean reaches = hasPathFrom(mc, p, e);
        recentReach.put(e.getId(), new long[]{now + 2_000, reaches ? 1 : 0});
        if (!reaches) {
            ignoredCreepers.put(e.getId(), now + IGNORE_NO_PATH_MS);
            Logbook.note("danger", String.format(
                    "creeper %.0f blocks away but with no path to me (neighbouring "
                    + "cave); I ignore it for %d s", p.distanceTo(e),
                    IGNORE_NO_PATH_MS / 1000));
        }
        if (recentReach.size() > 64) recentReach.clear();
        return reaches;
    }

    private static boolean hasPathFrom(Minecraft mc, LocalPlayer p, Entity e) {
        ClientWorld world = new ClientWorld(mc.level);
        int ex = (int) Math.floor(e.getX()), ey = (int) Math.floor(e.getY()),
            ez = (int) Math.floor(e.getZ());
        Route.Point from = null;
        for (int dy : new int[]{0, -1, 1}) {
            if (world.canStand(ex, ey + dy, ez)) {
                from = new Route.Point(ex, ey + dy, ez);
                break;
            }
        }
        // No clear tile to search from (swimming, falling, wedged into something odd):
        // when in doubt, it counts as danger.
        if (from == null) return true;
        Route.Point here = MarionetteBot.whereAmI(world, p);
        Route.Result r = Route.search(world, from, Route.Meta.near(here, 2.0),
                new Route.Options(8, 3_000, false, false).withDeadline(15));
        return r.hasRoute();
    }

    /**
     * Is it the nth scare from the same creeper in a short while without it touching me?
     * Ignore them for a while: a creeper that makes me flee three times in a minute and a
     * half and still does not arrive is one that cannot (a ledge, a one-block gap,
     * water), and to keep fleeing is to do nothing else all night.
     *
     * @return true if it must be IGNORED (and not fled from)
     */
    private boolean extraScare(Entity creeper, double d) {
        long now = System.currentTimeMillis();
        var queue = scares.computeIfAbsent(creeper.getId(),
                k -> new java.util.ArrayDeque<>());
        while (!queue.isEmpty() && now - queue.peekFirst() > SCARES_WINDOW_MS) {
            queue.pollFirst();
        }
        queue.addLast(now);
        if (queue.size() < SCARES_MAX) return false;
        queue.clear();
        ignoredCreepers.put(creeper.getId(), now + IGNORE_HEAVY_MS);
        Logbook.note("danger", String.format(
                "%d scares from the same creeper (%.0f blocks away) in a minute and "
                + "a half without it reaching me: I ignore it for %d s",
                SCARES_MAX, d, IGNORE_HEAVY_MS / 1000));
        Needs.warn("creeper_heavy:" + creeper.getId(), String.format(
                "a creeper %.0f blocks away made me flee %d times in a row without "
                + "reaching me; I ignore it for a minute and a half and carry on with my business",
                d, SCARES_MAX));
        return true;
    }

    /**
     * Phantoms are not fought from here: that belongs to the guard, which now does see
     * them. What is needed is to ask, because sleeping is not the bot's decision: it
     * skips the night for the whole server.
     *
     * <p>And only if they attack ME. A phantom in sight does not say whom it chases:
     * phantoms circling a player passed over the bot, and it warned "they are harassing
     * me" with one 24 blocks away. The phantom alert is for when phantoms attack the bot.
     * The proof that it is about me is the hit: {@link #aimAttacker} sees it in the
     * damage source, and from then on "being attacked" counts for a minute while one is
     * still nearby.
     */
    /**
     * The nearest phantom in sight (within SKY), or null. MarionetteBot's sleep-alone
     * routine checks it.
     */
    Entity phantomNear(Minecraft mc, LocalPlayer p) {
        return nearest(mc, p, SKY, e -> e instanceof Phantom);
    }

    private void phantoms(Minecraft mc, LocalPlayer p) {
        // With sleep_alone there is no asking: the body goes to bed
        // (MarionetteBot.sleepAlone) and only notifies if it has no bed or cannot manage.
        if (Preferences.is("sleep_alone")) return;
        if (System.currentTimeMillis() - lastPhantomHitMs > PHANTOM_ATTACKING_MS) {
            return;
        }
        Entity ph = nearest(mc, p, SKY, e -> e instanceof Phantom);
        if (ph == null) return;
        // Also in the brain's voice: asking for permission to sleep is in no hurry, and a
        // canned request sounds the same every night.
        if (Needs.warn("phantom", String.format(
                "phantoms are attacking me (one has hit me; the nearest "
                + "is %d blocks away). They come out from going three days without sleeping, "
                + "and sleeping gets rid of them — but that skips the night for the whole "
                + "server and it is not my decision",
                (int) p.distanceTo(ph)))) {
            Logbook.note("danger",
                    "a phantom hit me: notice to ask permission to sleep");
        }
    }

    /** The nearest living mob matching the condition, or null. */
    private static Entity nearest(Minecraft mc, LocalPlayer p, double radius,
                                     java.util.function.Predicate<Entity> which) {
        Entity best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity e : mc.level.getEntities(p,
                new AABB(p.blockPosition()).inflate(radius),
                e -> e.isAlive() && which.test(e))) {
            double d = p.distanceTo(e);
            if (d < bestDist) {
                bestDist = d;
                best = e;
            }
        }
        return best;
    }

    /** What the mob is called, for logbooks and notices: "zombie", "skeleton". */
    /** What can be seen, or what is so close that not seeing it does not matter. */
    private static boolean inSight(LocalPlayer p, Entity e) {
        return p.distanceTo(e) <= NEAR_BLIND || p.hasLineOfSight(e);
    }

    private static String name(Entity e) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
    }

    /**
     * Whether it is answering someone right now: with the bow or on top of them, either
     * way. Kept separate from `archery.killing` (that is the `kill` tool) because the TAB
     * only looked at that one and bow fights did not show as combat.
     */
    /**
     * Until when the last arrow at a creeper counts as a fight: without this, shooting
     * creepers did not show as combat in the TAB, because there is no attacker.
     */
    private long shootingUntil;

    private synchronized boolean fighting() {
        if (System.currentTimeMillis() < shootingUntil) return true;
        LocalPlayer p = Minecraft.getInstance().player;
        return attacker != null && attacker.isAlive() && p != null
                && p.distanceTo(attacker) <= DEFENSE;
    }

    synchronized String state() {
        return String.format(
                "{\"fleeing\":%b,\"retreating\":%b,\"fighting\":%b,"
                + "\"surfacing_to_breathe\":%b}",
                fleeing, retreating, fighting(), breathing);
    }

    /**
     * The health of something alive, 0 for what has none. The miss count only needs a
     * number that goes down when an arrow lands.
     */
    private static float healthOf(Entity e) {
        return e instanceof LivingEntity alive ? alive.getHealth() : 0f;
    }

}
