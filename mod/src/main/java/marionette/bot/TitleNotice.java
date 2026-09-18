package marionette.bot;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * What a person sees when this jar is installed in a client that is not a bot.
 *
 * <p>A line in a log is the right place to say something to a program and the wrong
 * place to say it to a person. Someone who sets up an instance, launches it and waits
 * for a bot that never obeys is not going to open latest.log to find out why — they are
 * going to look at the screen in front of them, decide the mod is broken, and leave.
 *
 * <p>So it is said on the title screen, which in a per-instance launcher is exactly
 * where that person is standing. Two different things are said, because two different
 * things happened:
 *
 * <ul>
 *   <li><b>The flag is there and empty.</b> Someone meant to make a bot and did not
 *       finish. This is a problem and reads like one.</li>
 *   <li><b>No flag at all.</b> Nothing is wrong: this is a player, and the mod is
 *       staying out of their way. It still says so, because a mod that is installed and
 *       silent is indistinguishable from a mod that is broken.</li>
 * </ul>
 *
 * <p>Only on the title screen, and only when this client is not a bot. A configured bot
 * never draws anything, and nothing is ever drawn over a world someone is playing in.
 */
public final class TitleNotice {

    /** Read by the test: the text belongs to the decision, not to the drawing. */
    static final String MISCONFIGURED =
            "Marionette: -Dmarionette.name is empty, so this instance is NOT a bot";
    static final String HINT_MISCONFIGURED =
            "Put a name in the instance's Java arguments, e.g. -Dmarionette.name=Alice";

    static final String NOT_A_BOT =
            "Marionette is installed but this instance is not a bot, so it does nothing";
    static final String HINT_NOT_A_BOT =
            "That is on purpose: play normally. To make a bot, see docs/setup.md";

    private static final int WARNING = 0xFFFF6B6B;
    private static final int CALM = 0xFFAAAAAA;
    private static final int SHADE = 0x90000000;

    private TitleNotice() {
    }

    /**
     * The two lines to show, or {@code null} to show nothing.
     *
     * <p>Kept apart from the drawing so it can be tested: what is said and when is a
     * decision, and putting pixels on a screen is not.
     */
    static String[] linesFor(boolean isBot, boolean misconfigured) {
        if (isBot) {
            return null;
        }
        return misconfigured
                ? new String[] {MISCONFIGURED, HINT_MISCONFIGURED}
                : new String[] {NOT_A_BOT, HINT_NOT_A_BOT};
    }

    @SubscribeEvent
    public static void onScreenRendered(ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof TitleScreen)) {
            return;
        }
        String[] lines = linesFor(Bot.isBot(), Bot.misconfigured());
        if (lines == null) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        GuiGraphics g = event.getGuiGraphics();
        int colour = Bot.misconfigured() ? WARNING : CALM;

        int width = 0;
        for (String line : lines) {
            width = Math.max(width, mc.font.width(line));
        }
        int height = lines.length * (mc.font.lineHeight + 2) + 6;
        int x = (event.getScreen().width - width) / 2;
        int y = 4;

        // A panorama is moving behind this: without something to sit on, the text is
        // legible in the dark half of the sky and gone in the bright half.
        g.fill(x - 6, y - 4, x + width + 6, y + height - 4, SHADE);
        for (int i = 0; i < lines.length; i++) {
            g.drawString(mc.font, lines[i], x, y + i * (mc.font.lineHeight + 2),
                    i == 0 ? colour : CALM);
        }
    }
}
