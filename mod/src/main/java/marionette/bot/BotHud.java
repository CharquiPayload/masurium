package marionette.bot;

import java.util.ArrayList;
import java.util.List;

/**
 * What is written over a bot's black screen, and which keys it offers.
 *
 * <p>A bot draws no world, on purpose: that is where nearly all the GPU of a Minecraft
 * client goes, and nobody is watching. What it leaves behind is a black window, and a
 * black window is indistinguishable from a crashed one. So the black is written on.
 *
 * <p>The HUD layer is drawn separately from the world, so none of this costs what
 * drawing the world costs — text on a flat colour, once per frame, at a capped frame
 * rate.
 *
 * <p>No Minecraft in this class, same split as {@link NoticeText}: what it says is a
 * decision with tests, drawing it is not.
 */
public final class BotHud {

    /** Minimise the window: the bot keeps working, it just stops taking up a screen. */
    public static final String KEY_MINIMISE = "M";
    /** Turn the world back on for a look, and off again. */
    public static final String KEY_RENDER = "R";

    private BotHud() {
    }

    /**
     * The lines to write over the black.
     *
     * @param name      the bot's name
     * @param server    where it was told to join, or {@code null}
     * @param connected whether it is in a world right now
     * @param rendering whether the world is being drawn at this moment
     */
    static List<String> lines(String name, String server, boolean connected,
                              boolean rendering) {
        List<String> out = new ArrayList<>();
        out.add("Marionette — " + name);

        if (connected) {
            out.add(server == null ? "In a world" : "In a world on " + server);
        } else if (server != null) {
            out.add("Joining " + server);
        } else {
            out.add("Not in a world");
        }

        // Said plainly, because a black screen is the one symptom everybody reads as a
        // crash. Whoever is looking at this needs to know it is deliberate before they
        // need anything else.
        out.add(rendering
                ? "Drawing the world — press " + KEY_RENDER + " to stop and save the GPU"
                : "Not drawing the world, to save GPU. Press " + KEY_RENDER + " to look");
        out.add("Press " + KEY_MINIMISE + " to minimise this window");
        return out;
    }
}
