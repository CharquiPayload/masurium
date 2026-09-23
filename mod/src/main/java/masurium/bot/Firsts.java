package masurium.bot;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import java.util.Set;

/**
 * First times.
 *
 * <p>The bot notices when it sees something for the first time in a world, and says so.
 * It is the cheapest thing there is and the one that gives the most "life": the
 * difference between a tool and someone who has been around for a while.
 *
 * <p>Almost everything is noted <b>silently</b> in the {@link Diary}: the first cow is
 * news to nobody, but a month later it is nice that it is written down. Only the big ones
 * are told ({@link #WORTH_TELLING}), because a bot that announces every chicken is a bot
 * that gets muted.
 *
 * <p>And <b>the brain</b> tells them, not this file: a canned sentence sounds the same in
 * every bot and every time. So this sends a body notice with the facts (what it is, how
 * far and where) and the one who speaks is upstairs, in its own words.
 *
 * <p>Urgent things stay canned on purpose ({@link Voice}): a creeper three blocks away
 * cannot wait the seconds a round trip to the model takes. Here there is no hurry; there
 * there is.
 */
final class Firsts {

    private Firsts() {}

    /** How often, in ticks, it looks. Two seconds: no hurry here. */
    private static final int EVERY = 40;
    /** Radius of the look. */
    private static final double VIEW = 32.0;

    /**
     * What deserves saying out loud the first time. Rare mobs, rarely seen or scary ones:
     * what you would tell someone about.
     */
    private static final Set<String> WORTH_TELLING = Set.of(
            "ender_dragon", "wither", "warden", "elder_guardian", "ravager",
            "evoker", "vindicator", "witch", "zombie_villager", "blaze",
            "ghast", "piglin_brute", "hoglin", "zoglin", "shulker", "guardian",
            "enderman", "iron_golem", "wither_skeleton", "allay", "axolotl",
            "sniffer", "camel", "panda", "polar_bear", "goat", "turtle");

    private static int ticks;

    static void tick() {
        if (++ticks < EVERY) return;
        ticks = 0;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || !p.isAlive()) return;

        String dim = Places.currentDimension();
        if (Diary.firstTime("dimension:" + dim)) {
            Diary.note("first time I set foot in " + dim);
            Needs.warn("first:dim:" + dim,
                    "it is the first time I set foot in " + dim);
        }

        for (Entity e : mc.level.getEntities(p,
                new AABB(p.blockPosition()).inflate(VIEW),
                x -> x instanceof LivingEntity && x.isAlive()
                     && !(x instanceof Player))) {
            String id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType())
                    .getPath();
            if (!Diary.firstTime("entity:" + id)) continue;
            Diary.note("first time I see a " + id);
            if (WORTH_TELLING.contains(id)) {
                // With all the facts: where it was and how far. Without them the brain
                // can only say "I saw one", and when asked which one it has nothing.
                Needs.warn("first:" + id, String.format(
                        "it is the first time I see a %s in this world; "
                        + "it was %d blocks away, at %d %d %d",
                        id, (int) p.distanceTo(e),
                        (int) Math.floor(e.getX()), (int) Math.floor(e.getY()),
                        (int) Math.floor(e.getZ())));
            }
        }
    }
}
