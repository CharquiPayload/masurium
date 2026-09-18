package marionette.bot;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.LightLayer;

/**
 * How the bot is, in one word, and why.
 *
 * <p>It gives the bot some character: without it, the bot answered the same with 3 hp in
 * the dark as with 20 in daylight, and that is not having character, it is not noticing.
 * The mood does not change what the bot DOES (behaviours decide that), but it shows up in
 * the state and the brain reads it before answering.
 *
 * <p>It is computed, not stored: it is the body looking at itself, not a counter someone
 * could leave wrong. And it comes with its reason, because "bruised" without saying why
 * is not information, it is a pose.
 *
 * <p>The order matters: the serious things are checked first. Being hungry with two hp is
 * not being hungry, it is being about to die.
 */
final class Mood {

    private Mood() {}

    private static final float HP_BAD = 7.0f;
    private static final float HP_TOUCHED = 14.0f;
    private static final int HUNGER_BAD = 6;

    /** JSON fragment with the mood and its reason, to put in the state. */
    static String asJson(Minecraft mc, LocalPlayer p) {
        String mood;
        String because;
        float hp = p.getHealth();
        int hunger = p.getFoodData().getFoodLevel();
        int light = mc.level == null ? 15
                : mc.level.getBrightness(LightLayer.BLOCK, p.blockPosition());
        boolean atNight = mc.level != null && !mc.level.isDay();

        if (!p.isAlive()) {
            mood = "dead";
            because = "I was killed and have not respawned yet";
        } else if (hp < HP_BAD) {
            mood = "badly hurt";
            because = String.format("I have %.0f hp left", hp);
        } else if (hunger <= HUNGER_BAD) {
            mood = "hungry";
            because = String.format("my hunger is at %d of 20", hunger);
        } else if (hp < HP_TOUCHED) {
            mood = "bruised";
            because = String.format("I am at %.0f hp of 20", hp);
        } else if (atNight && light == 0) {
            mood = "on guard";
            because = "it is night and I am in the dark, where mobs spawn";
        } else if (hp >= 19 && hunger >= 18) {
            mood = "fit";
            because = "hp and hunger full";
        } else {
            mood = "calm";
            because = "with nothing pressing me";
        }
        return String.format("{\"how\":\"%s\",\"because\":\"%s\"}",
                mood, because);
    }
}
