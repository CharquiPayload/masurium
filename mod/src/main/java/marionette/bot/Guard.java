package marionette.bot;

import marionette.common.Misses;
import marionette.common.Logbook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

/**
 * Hitting back. Without going through the model.
 *
 * <p>A zombie once killed the bot while the brain was thinking an answer. It carried a
 * diamond sword. The fight was not lost: <b>there was no fight</b>, because the brain
 * only runs when someone talks to it and a call can take two minutes. Nobody was watching
 * its health.
 *
 * <p>That is why this lives here and not in the agent: fighting is measured in ticks, and
 * a round trip to the model takes seconds. A defense that arrives late is no defense.
 *
 * <p>Deliberately short-sighted, so it does not turn into a bot that decides on its own:
 * <ul>
 *   <li>it only reacts <b>after taking damage</b>, it never attacks first;
 *   <li>only against what is already on top of it ({@value #REACH} blocks): <b>it does
 *       not chase</b>; chasing at night is exactly how it died;
 *   <li>it does not touch the walk: if it was going somewhere, it keeps going while it
 *       hits.
 * </ul>
 */
final class Guard {

    /** Who gets hit back: whoever is on top of it, not "somewhere around". */
    private static final double REACH = 3.0;
    /** How far it looks to find whoever is hitting. */
    private static final double VIEW = 6.0;
    /** How long the alert lasts after the last bite. */
    private static final int ALERT_TICKS = 20 * 8;
    /** Half health. Below it a notice goes out: the agent decides whether to flee. */
    private static final float HP_LOW = 6.0f;
    /**
     * Hits from the same player before saying something. One is an accident (or a stray
     * arrow); three in a row are not.
     */
    private static final int COMPLAINT_HITS = 3;
    /**
     * And within what time: three hits in twenty seconds are a beating, three in two
     * hours are three accidents.
     */
    private static final int WINDOW = 20 * 20;

    private float previousHp = -1;
    /** Who is hitting it, how many times, and when the last one was. */
    private String hittingMe;
    private int hits;
    private int ticksSinceHit = Integer.MAX_VALUE / 2;
    private int alert;
    private boolean wasAlive = true;
    private boolean warnedLowHealth;

    /**
     * @param busy whether it is walking: then its head is not turned
     * @param inCrosshair the player IT is attacking now, or null: no complaint about that
     *                    one even if it hits back, since the bot started that fight
     */
    void tick(boolean busy, String inCrosshair) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) {
            previousHp = -1;
            return;
        }

        if (!p.isAlive()) {
            if (wasAlive) {
                // The line that was missing the night it died.
                Logbook.note("death", String.format(
                        "I was killed at %.0f %.0f %.0f", p.getX(), p.getY(), p.getZ()));
                // And the spot is SAVED and notified: the logbook lives in memory and is
                // lost on restart, so the grave is noted as a place. Only the last one:
                // an older grave has nothing left to pick up. The position goes in the
                // notice key so two deaths in different places count as two notices and
                // the cooldown does not silence the second.
                BlockPos grave = p.blockPosition();
                // Who killed me, if it was someone: for the diary and for what I know
                // about that person.
                var source = p.getLastDamageSource();
                String culprit = source != null
                        && source.getEntity() instanceof Player pj
                        ? pj.getGameProfile().getName() : null;
                if (culprit != null) People.tally(culprit, "killed_me");
                Diary.note(String.format("I was killed at %d %d %d%s",
                        grave.getX(), grave.getY(), grave.getZ(),
                        culprit == null ? "" : ", and it was " + culprit));
                Places.forgetType("death");
                Places.remember("death", grave, "where I was killed");
                // Was it the terrain? Then the grave is a trap and ItemRecovery must not
                // go back: see ItemRecovery.trapGrave.
                String terrain = null;
                if (source != null) {
                    if (source.is(DamageTypes.HOT_FLOOR)) terrain = "magma";
                    else if (source.is(DamageTypes.LAVA)) terrain = "lava";
                    else if (source.is(DamageTypes.DROWN)) terrain = "I drowned";
                    else if (source.is(DamageTypeTags.IS_FIRE)) terrain = "fire";
                }
                if (terrain != null) {
                    ItemRecovery.trapGrave(terrain);
                    Logbook.note("death", "the terrain killed me: " + terrain);
                }
                Needs.warn("death:" + grave.asLong(), String.format(
                        "I was killed at %d %d %d and I noted it as a place of "
                        + "type death; check whether I kept my inventory and, "
                        + "if not, everything stayed there",
                        grave.getX(), grave.getY(), grave.getZ()));
                wasAlive = false;
            }
            previousHp = -1;
            alert = 0;
            return;
        }
        wasAlive = true;

        ticksSinceHit++;
        float hp = p.getHealth();
        if (previousHp >= 0 && hp < previousHp - 0.01f) {
            alert = ALERT_TICKS;
            Logbook.note("damage", String.format(
                    "I was hit: %.1f -> %.1f hp", previousHp, hp));
            // Whoever reaches me earns arrows again: an ignored target stops being
            // ignored the moment it proves it can hurt me (see Misses). For an arrow
            // or a fireball the source is whoever shot it, not the projectile.
            var blow = p.getLastDamageSource();
            Entity attacker = blow == null ? null : blow.getEntity();
            if (attacker != null) Misses.hurtMe(attacker.getUUID());
            complainIfPlayer(p, inCrosshair);
        }
        if (hp <= HP_LOW && !warnedLowHealth) {
            Logbook.note("danger", String.format(
                    "low hp: %.1f of 20", hp));
            warnedLowHealth = true;
        } else if (hp > HP_LOW + 2) {
            warnedLowHealth = false;      // with hysteresis, or it notifies in a loop
        }
        previousHp = hp;

        if (alert <= 0) return;
        alert--;

        Entity who = theNearest(mc, p);
        if (who == null) return;

        // Wielding costs a tick ON PURPOSE: switching items resets the attack bar, so
        // hitting in the same tick as the switch does a fraction of the damage (three
        // hits of 0.9 with an axe that does 9). And that is why it compares first:
        // calling wield every tick would reset the bar forever and it would never really
        // hit.
        int weapon = WeaponPicker.prepareWeapon(mc, p);
        if (p.getInventory().selected != weapon) {
            p.getInventory().selected = weapon;
            Logbook.note("defense", "wielding " + inHand(p) + " to defend myself");
            return;
        }
        if (p.getAttackStrengthScale(0.0f) < 0.9f) return;

        // Turn the head only when not walking: the walker pushes towards where the bot
        // looks, and looking at the mob would make it walk towards the mob.
        if (!busy) {
            p.lookAt(EntityAnchorArgument.Anchor.EYES,
                     who.position().add(0, who.getBbHeight() / 2, 0));
        }
        mc.gameMode.attack(p, who);
        p.swing(InteractionHand.MAIN_HAND);
        Logbook.note("defense", String.format(
                "I hit %s back with %s",
                BuiltInRegistries.ENTITY_TYPE.getKey(who.getType()).getPath(),
                inHand(p)));
    }

    /**
     * Being hit by a player is not the same as being hit by a zombie. It notifies when
     * someone hits the bot too much, except when it is whoever the bot itself is hunting.
     *
     * <p>The notice goes to the body and not to the chat on purpose: what to say to
     * whoever is hitting it is the brain's decision, not a canned message. Here it only
     * counts to three.
     */
    private void complainIfPlayer(LocalPlayer p, String inCrosshair) {
        var source = p.getLastDamageSource();
        if (source == null || !(source.getEntity() instanceof Player pj)) return;
        if (pj == p) return;                       // not its own mistakes
        String name = pj.getGameProfile().getName();
        if (name.equalsIgnoreCase(inCrosshair)) return;   // it started that one
        if (!name.equals(hittingMe) || ticksSinceHit > WINDOW) {
            hittingMe = name;
            hits = 0;
        }
        ticksSinceHit = 0;
        People.tally(name, "hits");
        if (++hits < COMPLAINT_HITS) return;
        hits = 0;                                 // and count again
        Needs.warn("hit:" + name, String.format(
                "%s is hitting me (%d hits in a row) and I am not "
                + "doing anything to them", name, COMPLAINT_HITS));
    }

    /**
     * The nearest living hostile already within reach, or null.
     *
     * <p>Hostile is {@code Enemy}, not {@code Monster}, and the difference is not
     * academic: PHANTOMS are not Monsters (they fly and inherit from another branch), and
     * for weeks the guard simply did not see them while they pecked at the bot. This line
     * is half the answer to one of its two usual causes of death.
     *
     * <p>And CREEPERS are left out ON PURPOSE: hitting a creeper back means staying next
     * to it while it swells, which is the other cause of death. The {@link Lookout}
     * handles them: it knows how to back away and shoot them from a distance.
     */
    private static Entity theNearest(Minecraft mc, LocalPlayer p) {
        Entity best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity e : mc.level.getEntities(p,
                new AABB(p.blockPosition()).inflate(VIEW),
                e -> e instanceof Enemy && e.isAlive()
                     && !(e instanceof Creeper))) {
            double d = p.distanceTo(e);
            if (d < bestDist) {
                bestDist = d;
                best = e;
            }
        }
        return bestDist <= REACH ? best : null;
    }

    private static String inHand(LocalPlayer p) {
        var stack = p.getMainHandItem();
        return stack.isEmpty() ? "my hands"
                : BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
    }
}
