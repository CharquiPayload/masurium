package marionette.bot;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import org.lwjgl.glfw.GLFW;

/**
 * The bot's own window: what it shows and the two keys it answers.
 *
 * <p>Drawing the world is switched off for a bot because that is where a Minecraft
 * client spends its graphics card, and there is nobody watching. What is left is a black
 * window, which looks exactly like a crashed one — so {@link BotHud} writes over it, and
 * this class puts those lines on the screen and reads the keys.
 *
 * <p>Two keys, and both exist because a bot now runs where a person can see it:
 *
 * <ul>
 *   <li><b>M</b> minimises. The bot keeps working; it just stops occupying a screen.</li>
 *   <li><b>R</b> draws the world, and stops again. Looking at a bot used to mean
 *       nothing: the window was black and stayed black. Now it costs a keypress and
 *       only for as long as you are looking.</li>
 * </ul>
 *
 * <p>Neither key does anything while a screen is open, so typing an M in the chat is
 * still typing an M.
 */
public final class BotScreen {

    /** Whether the world is being drawn right now. Read by the render mixin. */
    private static boolean rendering;

    private static final int SHADE = 0x90000000;
    private static final int TITLE = 0xFF7FD67F;
    private static final int TEXT = 0xFFCCCCCC;

    private BotScreen() {
    }

    /** Whether a bot should draw the world this frame. */
    public static boolean drawing() {
        return rendering;
    }

    @SubscribeEvent
    public static void onKey(InputEvent.Key event) {
        if (!Bot.isBot() || event.getAction() != InputConstants.PRESS) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        // A key pressed into a screen belongs to that screen: an M typed in the chat is
        // an M, not a command.
        if (mc.screen != null) {
            return;
        }
        switch (event.getKey()) {
            case GLFW.GLFW_KEY_M -> GLFW.glfwIconifyWindow(mc.getWindow().getWindow());
            case GLFW.GLFW_KEY_R -> rendering = !rendering;
            default -> { }
        }
    }

    @SubscribeEvent
    public static void onGui(RenderGuiEvent.Post event) {
        if (!Bot.isBot() || rendering) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        GuiGraphics g = event.getGuiGraphics();

        var lines = BotHud.lines(Bot.name(), Bot.server(),
                mc.level != null && mc.player != null, rendering);

        int step = mc.font.lineHeight + 3;
        int width = 0;
        for (String line : lines) {
            width = Math.max(width, mc.font.width(line));
        }
        int x = (g.guiWidth() - width) / 2;
        int y = (g.guiHeight() - lines.size() * step) / 2;

        g.fill(x - 8, y - 6, x + width + 8, y + lines.size() * step + 2, SHADE);
        for (int i = 0; i < lines.size(); i++) {
            g.drawString(mc.font, lines.get(i), x, y + i * step, i == 0 ? TITLE : TEXT);
        }
    }
}
